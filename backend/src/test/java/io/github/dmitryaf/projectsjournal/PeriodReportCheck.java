package io.github.dmitryaf.projectsjournal;

import tools.jackson.databind.json.JsonMapper;

public final class PeriodReportCheck {
    private PeriodReportCheck() {
    }

    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        var reports = new PeriodReport(mapper, new AnalysisPrivacy(mapper));
        var input = mapper.readTree(System.in);
        reports.validate(input.required("payload"), input.required("report"));
    }
}
