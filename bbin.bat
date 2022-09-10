@echo off
set ARGS=%*
set SCRIPT=%~dp0bbin
bb -f %SCRIPT% %ARGS%
