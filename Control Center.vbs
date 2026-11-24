Option Explicit
Dim shell, files, root
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
root = files.GetParentFolderName(WScript.ScriptFullName)
' Keep existing Windows shortcuts working after the rename.
shell.Run "wscript.exe """ & root & "\Projects Journal.vbs""", 0, False
