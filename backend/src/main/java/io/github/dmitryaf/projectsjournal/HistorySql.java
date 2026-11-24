package io.github.dmitryaf.projectsjournal;

final class HistorySql {

    private HistorySql() {
    }

    // The journal projects current entries from their source tables without storing a second copy.
    static final String ENTRIES = """
            WITH options(zone) AS (VALUES(?)),
            entries(key,kind,event,sourceId,day,recordedAt,dateQuality,projectId,scopeProjectId,projectName,title,body,href) AS (
              SELECT 'project:'||e.scope_project_id||':'||printf('%010d',e.revision),'project','project_'||e.action,e.id,
                history_day(e.recorded_at,(SELECT zone FROM options)),e.recorded_at,'recorded',e.project_id,e.scope_project_id,e.project_name,
                CASE WHEN e.action='reopened' THEN 'Проект возвращён в работу' WHEN e.outcome='stopped' THEN 'Больше не развиваю' ELSE 'Проект завершён' END,
                e.reason,'/project-lifecycle/'||e.id
                FROM project_lifecycle_events e
              UNION ALL
              SELECT 'task:'||h.id,'task','task_'||h.kind,CAST(h.id AS TEXT),history_day(h.recorded_at,(SELECT zone FROM options)),h.recorded_at,
                CASE WHEN h.kind='baseline' THEN 'baseline' ELSE 'recorded' END,h.project_id,COALESCE(h.project_id,json_extract(h.snapshot,'$.projectId')),h.project_name,
                json_extract(h.snapshot,'$.code')||' · '||json_extract(h.snapshot,'$.title'),
                CASE WHEN h.kind='reopened' THEN '' WHEN h.kind='cancelled' THEN 'Задача отменена при закрытии проекта.' ELSE COALESCE(json_extract(h.snapshot,'$.result.summary'),'') END,
                '/history?task='||json_extract(h.snapshot,'$.code') FROM task_history h
              UNION ALL
              SELECT 'check:start:'||c.id,'check','check_started',c.id,json_extract(c.data,'$.startedAt'),NULL,'declared',c.project_id,COALESCE(c.project_id,json_extract(c.data,'$.projectId')),
                COALESCE(json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.yaml.metadata.name'),json_extract(p.snapshot,'$.directoryName')),
                json_extract(c.data,'$.title'),json_extract(c.data,'$.question'),'/checks/'||c.id
                FROM checks c LEFT JOIN projects p ON p.id=c.project_id
              UNION ALL
              SELECT 'check:end:'||c.id,'check','check_completed',c.id,json_extract(c.data,'$.completedAt'),NULL,'declared',c.project_id,COALESCE(c.project_id,json_extract(c.data,'$.projectId')),
                COALESCE(json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.yaml.metadata.name'),json_extract(p.snapshot,'$.directoryName')),
                json_extract(c.data,'$.title'),json_extract(c.data,'$.conclusion.actualResult'),'/checks/'||c.id
                FROM checks c LEFT JOIN projects p ON p.id=c.project_id WHERE json_extract(c.data,'$.status')='completed'
              UNION ALL
              SELECT 'check:entry:'||e.id,'check','check_'||json_extract(e.data,'$.kind'),e.id,json_extract(e.data,'$.occurredAt'),NULL,'declared',c.project_id,COALESCE(c.project_id,json_extract(c.data,'$.projectId')),
                COALESCE(json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.yaml.metadata.name'),json_extract(p.snapshot,'$.directoryName')),
                json_extract(c.data,'$.title'),json_extract(e.data,'$.text'),'/checks/'||c.id||'#entry-'||e.id
                FROM check_entries e JOIN checks c ON c.id=e.check_id LEFT JOIN projects p ON p.id=c.project_id
              UNION ALL
              SELECT 'decision:'||r.id,'decision','decision',r.id,json_extract(r.data,'$.date'),NULL,'declared',r.project_id,r.project_id,
                COALESCE(json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.yaml.metadata.name'),json_extract(p.snapshot,'$.directoryName')),
                json_extract(r.data,'$.title'),json_extract(r.data,'$.decision'),'/decisions#decision-'||r.id
                FROM records r LEFT JOIN projects p ON p.id=r.project_id WHERE r.kind='decisions'
              UNION ALL
              SELECT json_extract(value,'$.key'),'file_decision','file_decision',json_extract(value,'$.sourceId'),json_extract(value,'$.day'),NULL,'declared',
                json_extract(value,'$.projectId'),json_extract(value,'$.projectId'),json_extract(value,'$.projectName'),json_extract(value,'$.title'),'',json_extract(value,'$.href') FROM json_each(?)
              UNION ALL
              SELECT 'observation:'||o.id,'observation','observation',o.id,o.occurred_on,NULL,'declared',o.project_id,o.scope_project_id,o.project_name,
                substr(o.body,1,120),o.body,'/observations?id='||o.id FROM observations o
              UNION ALL
              SELECT 'review:'||r.id,'review','review',r.id,r.period_start,NULL,'declared',r.project_id,
                CASE WHEN r.scope_key='general' THEN NULL ELSE substr(r.scope_key,9) END,r.project_name,
                CASE WHEN r.period_kind='week' THEN 'Обзор недели · ' ELSE 'Обзор месяца · ' END||r.period_start||' — '||r.period_end,
                r.user_text,'/reviews?id='||r.id FROM reviews r
              UNION ALL
              SELECT 'analysis:'||a.id,'ai_analysis','ai_analysis',a.id,history_day(a.created_at,(SELECT zone FROM options)),a.created_at,'recorded',p.id,
                CASE WHEN a.scope_key='general' THEN NULL ELSE substr(a.scope_key,9) END,
                COALESCE(json_extract(a.payload_json,'$.scope.name'),json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.directoryName')),
                CASE WHEN json_extract(a.payload_json,'$.origin.kind')='codex_manual' THEN 'Codex вручную · ' WHEN json_extract(a.payload_json,'$.origin.kind')='codex_runtime' THEN 'Codex · ' ELSE 'AI · ' END||json_extract(a.payload_json,'$.period.from')||' — '||json_extract(a.payload_json,'$.period.to')||' · '||a.generation||' · '||a.status,
                COALESCE(json_extract(a.report_json,'$.narrative'),a.error,''),'/period-analysis?review='||a.review_key||'&run='||a.id
                FROM analysis_runs a LEFT JOIN projects p ON p.id=substr(a.scope_key,9)
            )
            """;

    static final String LATEST_SCOPED = """
            , scoped AS (
              SELECT entries.*,scopeProjectId AS selectedScope FROM entries WHERE kind!='task'
              UNION ALL
              SELECT entries.*,links.value AS selectedScope FROM entries
              JOIN task_history h ON entries.kind='task' AND h.id=CAST(entries.sourceId AS INTEGER)
              JOIN json_each(%s) links
            ), ranked AS (
              SELECT *,row_number() OVER(PARTITION BY selectedScope ORDER BY day DESC,COALESCE(recordedAt,'') DESC,key DESC) AS position
              FROM scoped WHERE selectedScope IN (SELECT id FROM projects)
            )
            """.formatted(TaskService.projectIdsSql("h.snapshot"));
}
