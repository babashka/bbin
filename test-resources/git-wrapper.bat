@echo off
set ARGS=%*
set SCRIPT=%~dp0git-wrapper
bb -f %SCRIPT% %ARGS%
