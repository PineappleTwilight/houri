@echo off
REM Wipe the llamatik-native CMake/Gradle caches (Windows).
REM
REM Why this exists (mirrors external/imagedecoder-houri/BUILD.md section 6):
REM   AGP keeps .cxx\<Variant>\<hash>\<abi>\CMakeCache.txt between runs. That cache pins the
REM   results of find_program() and our own set(... CACHE "" FORCE) calls, so a configure that
REM   could not see glslc keeps reporting NOTFOUND even after the tool appears -- and a probed
REM   WSL proxy stays pinned too. The symptom is "I fixed CMakeLists.txt and nothing changed".
REM   Also: a 32-bit Gradle daemon cannot see C:\Windows\System32\wsl.exe (it is redirected to
REM   SysWOW64), so it can cache a bogus "no WSL" probe result.
REM
REM Run it after editing CMakeLists.txt, switching NDK, or changing any -Pmtl.* property.
REM
REM   wipe-cache.bat
REM   wipe-cache.bat --all                        also clear build-script caches
REM   wipe-cache.bat --all --build                ...then build
REM   wipe-cache.bat --build :llamatik-native:assembleDebug -Pmtl.gpuOffload=false
REM
REM Safe to run at any time; everything removed here is generated and re-derivable.

setlocal enabledelayedexpansion

set "HERE=%~dp0"
for %%I in ("%HERE%..") do set "REPO=%%~fI"

set "DO_BUILD=0"
if /I "%~1"=="--build" (
  set "DO_BUILD=1"
  shift
)

REM --all also clears the build-script caches. That is what fixes
REM "DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet",
REM which is a stale AGP jar / regenerated decorator rather than anything in .cxx.
set "DEEP=0"
if /I "%~1"=="--all" (
  set "DEEP=1"
  shift
)

REM Stop the daemon first: a warm daemon can hold the CMake cache in memory and write it
REM back after we delete the files, which resurrects the stale state.
echo ==^> stopping Gradle daemons
call "%REPO%\gradlew.bat" --stop >nul 2>&1

call :wipe "%HERE%.cxx" ".cxx"
call :wipe "%REPO%\.gradle\configuration-cache" ".gradle\configuration-cache"
if "%DEEP%"=="1" (
  call :wipe "%REPO%\.gradle" ".gradle"
  call :wipe "%REPO%\buildSrc\.gradle" "buildSrc\.gradle"
  call :wipe "%REPO%\buildSrc\build" "buildSrc\build"
)

if "%DO_BUILD%"=="1" (
  if "%~1"=="" (
    set "ARGS=:llamatik-native:assembleDebug"
  ) else (
    set "ARGS=%*"
  )
  echo ==^> building: %ARGS%
  call "%REPO%\gradlew.bat" %ARGS% --no-configuration-cache --rerun-tasks
  exit /b %ERRORLEVEL%
)

echo.
echo Done. Now re-run your build (the next configure will re-probe glslc).
exit /b 0

:wipe
if exist "%~1" (
  echo ==^> removing %~2
  rmdir /s /q "%~1"
) else (
  echo ==^> absent  %~2
)
exit /b 0
