@echo off
rem Copy to config-local.bat, then fill in your own database password and model settings.
set "BURN_AI_DB_USERNAME=root"
set "BURN_AI_DB_PASSWORD=YOUR_MYSQL_PASSWORD"
rem Default database: burn_treatment. To use another database, uncomment and edit:
rem set "BURN_AI_DB_URL=jdbc:mysql://127.0.0.1:3306/burn_treatment?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
set "BURN_AI_PROVIDER=qwen"
set "BURN_AI_QWEN_ENABLED=true"
set "BURN_AI_QWEN_API_KEY=YOUR_QWEN_API_KEY"
set "BURN_AI_QWEN_MODEL=qwen-plus"
set "BURN_AI_VLLM_ENABLED=false"
rem Optional if tools are not on PATH:
rem set "JAVA_CMD=C:\path\to\jdk-17\bin\java.exe"
rem set "MAVEN_CMD=C:\path\to\apache-maven\bin\mvn.cmd"
