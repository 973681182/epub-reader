' EPUB Reader Launcher
Set ws = CreateObject("Wscript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
ws.CurrentDirectory = dir

java = ws.ExpandEnvironmentStrings("%JAVA_HOME%\bin\java.exe")
If Not fso.FileExists(java) Then java = "java.exe"

ws.Run """" & java & """ -Xmx512m -Dfile.encoding=UTF-8 -jar """ & dir & "\epub-reader.jar""", 1, False
