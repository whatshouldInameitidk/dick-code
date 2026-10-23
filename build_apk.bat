@echo off
setlocal enabledelayedexpansion
title InvernessLoader - APK Builder & Release Tool

:: Ensure working directory is BlackCore-3am
cd /d "D:\Projects\BlackCore-3am"

:: Set Java Environment if not already configured
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
        set "PATH=%JAVA_HOME%\bin;%PATH%"
    )
)

:MENU
cls
echo ========================================================================
echo                   INVERNESSLOADER - APK BUILD TOOL
echo ========================================================================

:: Read current version from app/build.gradle
set "CURRENT_VER=Unknown"
set "CURRENT_CODE=Unknown"
for /f "tokens=2 delims= " %%A in ('findstr /i "versionName" app\build.gradle') do (
    set "CURRENT_VER=%%~A"
)
for /f "tokens=2 delims= " %%A in ('findstr /i "versionCode" app\build.gradle') do (
    set "CURRENT_CODE=%%~A"
)

echo  Project Location: D:\Projects\BlackCore-3am
echo  Current Version : v!CURRENT_VER! (versionCode: !CURRENT_CODE!)
echo  Output APK Path : app\build\outputs\apk\BlackBox64\debug\InvernessLoader.apk
echo ========================================================================
echo.
echo   [1] Compile APK (assembleBlackBox64Debug)
echo   [2] Compile APK and Install to Phone (via ADB)
echo   [3] Compile, Install and Launch on Phone
echo   [4] Update Version, Compile and Push to GitHub (Release)
echo   [5] Update Native Lib SHA-256 only (from Inverness64bit)
echo   [6] Clean Project (gradlew clean)
echo   [0] Exit
echo.
echo ========================================================================
set /p CHOICE="Enter your choice [1-6, 0]: "

if "%CHOICE%"=="1" goto COMPILE_ONLY
if "%CHOICE%"=="2" goto COMPILE_AND_INSTALL
if "%CHOICE%"=="3" goto COMPILE_INSTALL_LAUNCH
if "%CHOICE%"=="4" goto RELEASE_GITHUB
if "%CHOICE%"=="5" goto SYNC_SHA
if "%CHOICE%"=="6" goto CLEAN_PROJECT
if "%CHOICE%"=="0" goto EXIT_SCRIPT

echo Invalid option selected.
ping 127.0.0.1 -n 2 >nul
goto MENU

:COMPILE_ONLY
echo.
echo ========================================================================
echo  Compiling InvernessLoader (v!CURRENT_VER!)...
echo ========================================================================
call .\gradlew.bat :app:assembleBlackBox64Debug
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed! Check the errors above.
    pause
    goto MENU
)

set "APK_FILE=D:\Projects\BlackCore-3am\app\build\outputs\apk\BlackBox64\debug\InvernessLoader.apk"
if exist "!APK_FILE!" (
    echo.
    echo ========================================================================
    echo  [SUCCESS] APK Compiled Successfully!
    echo  Location: !APK_FILE!
    for %%F in ("!APK_FILE!") do (
        set /a SIZE_MB=%%~zF / 1048576
        echo  File Size: !SIZE_MB! MB (%%~zF bytes)
        echo  Built At : %%~tF
    )
    echo ========================================================================
    echo.
    set /p OPEN_DIR="Open output folder in Explorer? (Y/N) [Y]: "
    if /i "!OPEN_DIR!" NEQ "N" (
        explorer.exe /select,"!APK_FILE!"
    )
) else (
    echo [WARNING] APK file not found at expected location.
)
pause
goto MENU

:COMPILE_AND_INSTALL
echo.
echo ========================================================================
echo  Compiling InvernessLoader (v!CURRENT_VER!)...
echo ========================================================================
call .\gradlew.bat :app:assembleBlackBox64Debug
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed!
    pause
    goto MENU
)

set "APK_FILE=D:\Projects\BlackCore-3am\app\build\outputs\apk\BlackBox64\debug\InvernessLoader.apk"
echo.
echo ========================================================================
echo  Checking connected ADB devices...
echo ========================================================================
adb devices
echo.
echo Installing !APK_FILE! to device...
adb install -r "!APK_FILE!"
if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Installed to phone successfully!
) else (
    echo.
    echo [ERROR] ADB installation failed. Make sure your phone is connected with USB Debugging enabled.
)
pause
goto MENU

:COMPILE_INSTALL_LAUNCH
echo.
echo ========================================================================
echo  Compiling InvernessLoader (v!CURRENT_VER!)...
echo ========================================================================
call .\gradlew.bat :app:assembleBlackBox64Debug
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed!
    pause
    goto MENU
)

set "APK_FILE=D:\Projects\BlackCore-3am\app\build\outputs\apk\BlackBox64\debug\InvernessLoader.apk"
echo.
echo Installing !APK_FILE!...
adb install -r "!APK_FILE!"
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] Installed! Launching InvernessLoader on phone...
    adb shell am start -n top.niunaijun.blackboxa64/top.niunaijun.blackboxa.view.main.WelcomeActivity
) else (
    echo [ERROR] ADB install failed.
)
pause
goto MENU

:SYNC_SHA
echo.
echo ========================================================================
echo  Checking SHA-256 for native library...
echo ========================================================================
set "SO_FILE=D:\Projects\Inverness64bit\libs\arm64-v8a\InvernessAndriod.so"
if not exist "!SO_FILE!" (
    echo [ERROR] Native lib not found at: !SO_FILE!
    pause
    goto MENU
)

set "NEW_SHA="
for /f "skip=1 tokens=1" %%H in ('certutil -hashfile "!SO_FILE!" SHA256') do (
    if not defined NEW_SHA set "NEW_SHA=%%H"
)

echo Detected SHA-256: !NEW_SHA!
echo Updating HomeFragment.kt with this SHA...

python -c "
import re
home = r'D:\Projects\BlackCore-3am\app\src\main\java\top\niunaijun\blackboxa\view\main\HomeFragment.kt'
with open(home, 'r', encoding='utf-8') as f:
    c = f.read()
sha = '!NEW_SHA!'.lower()
c = re.sub(r'\"[a-f0-9]{64}\"', f'\"{sha}\"', c)
with open(home, 'w', encoding='utf-8') as f:
    f.write(c)
print('Updated SHA in HomeFragment.kt to:', sha)
"

if exist "D:\Projects\InvernessLoader" (
    echo Copying InvernessAndriod.so to InvernessLoader repo...
    copy /y "!SO_FILE!" "D:\Projects\InvernessLoader\InvernessAndriod.so" >nul
)
echo [SUCCESS] Native library SHA synced!
pause
goto MENU

:RELEASE_GITHUB
echo.
echo ========================================================================
echo               GLOBAL RELEASE TO GITHUB (InvernessLoader)
echo ========================================================================
echo  Current Version: !CURRENT_VER! (versionCode: !CURRENT_CODE!)
echo.
set /p NEW_VER="Enter NEW Version Name [Press Enter to keep !CURRENT_VER!]: "
if "!NEW_VER!"=="" set "NEW_VER=!CURRENT_VER!"

set /a NEXT_CODE=!CURRENT_CODE! + 1
set /p NEW_CODE="Enter NEW Version Code [Press Enter for !NEXT_CODE!]: "
if "!NEW_CODE!"=="" set "NEW_CODE=!NEXT_CODE!"

echo.
echo Updating app/build.gradle to version !NEW_VER! (code !NEW_CODE!)...
python -c "
import re
path = r'D:\Projects\BlackCore-3am\app\build.gradle'
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()
c = re.sub(r'versionCode\s+\d+', 'versionCode !NEW_CODE!', c)
c = re.sub(r'versionName\s+\"[^\"]+\"', 'versionName \"!NEW_VER!\"', c)
with open(path, 'w', encoding='utf-8') as f:
    f.write(c)
"

echo Updating D:\Projects\InvernessLoader\remote_version.txt to !NEW_VER!...
python -c "
with open(r'D:\Projects\InvernessLoader\remote_version.txt', 'w', encoding='utf-8') as f:
    f.write('!NEW_VER!\n')
"

:: Sync native SHA if file exists
set "SO_FILE=D:\Projects\Inverness64bit\libs\arm64-v8a\InvernessAndriod.so"
if exist "!SO_FILE!" (
    echo Syncing latest InvernessAndriod.so...
    copy /y "!SO_FILE!" "D:\Projects\InvernessLoader\InvernessAndriod.so" >nul
    set "NEW_SHA="
    for /f "skip=1 tokens=1" %%H in ('certutil -hashfile "!SO_FILE!" SHA256') do (
        if not defined NEW_SHA set "NEW_SHA=%%H"
    )
    python -c "
import re
home = r'D:\Projects\BlackCore-3am\app\src\main\java\top\niunaijun\blackboxa\view\main\HomeFragment.kt'
with open(home, 'r', encoding='utf-8') as f:
    c = f.read()
sha = '!NEW_SHA!'.lower()
c = re.sub(r'\"[a-f0-9]{64}\"', f'\"{sha}\"', c)
with open(home, 'w', encoding='utf-8') as f:
    f.write(c)
"
)

echo.
echo Compiling release APK...
call .\gradlew.bat :app:assembleBlackBox64Debug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed! Aborting push.
    pause
    goto MENU
)

set "SRC_APK=D:\Projects\BlackCore-3am\app\build\outputs\apk\BlackBox64\debug\InvernessLoader.apk"
set "DST_APK=D:\Projects\InvernessLoader\InvernessLoader.apk"
echo Copying APK to !DST_APK!...
copy /y "!SRC_APK!" "!DST_APK!" >nul

echo.
echo ========================================================================
echo  Pushing to GitHub (InvernessLoader)...
echo ========================================================================
cd /d "D:\Projects\InvernessLoader"
git add InvernessLoader.apk remote_version.txt InvernessAndriod.so
git commit -m "release: v!NEW_VER! - global update"
git push origin main
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================================
    echo  [SUCCESS] v!NEW_VER! successfully published to GitHub!
    echo  Remember to update the Application Version in KeyAuth to !NEW_VER!.
    echo ========================================================================
) else (
    echo.
    echo [ERROR] git push failed. Check your network or git credentials.
)
cd /d "D:\Projects\BlackCore-3am"
pause
goto MENU

:CLEAN_PROJECT
echo.
echo Cleaning Gradle build artifacts...
call .\gradlew.bat clean
echo.
echo [SUCCESS] Clean completed.
pause
goto MENU

:EXIT_SCRIPT
exit /b 0
