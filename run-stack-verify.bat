@echo off
rem Runs the whole-stack verification script under Git Bash (MSYS2), whose
rem POSIX-to-Windows argument translation is required by the native docker.exe
rem CLI. The Cygwin bash on PATH does not perform that translation.
"C:\Program Files\Git\bin\bash.exe" --noprofile --norc scripts/verify-container-stack.sh
