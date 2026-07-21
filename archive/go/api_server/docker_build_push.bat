@echo off
setlocal

set DOCKER_USERNAME=jiangfz
set IMAGE_NAME=online-chatroom-apiserver
set FULL_IMAGE=%DOCKER_USERNAME%/%IMAGE_NAME%:latest

echo.
echo Build image: %FULL_IMAGE%
docker build -t %FULL_IMAGE% .

if %errorlevel% neq 0 (
    echo  Docker build failed！
    exit /b 1
)

echo.
echo Push to Docker Hub...
docker push %FULL_IMAGE%

if %errorlevel% neq 0 (
    echo  Docker push failed！
    exit /b 1
)

echo.
echo Image push success！
echo Image address: %FULL_IMAGE%

endlocal
pause
