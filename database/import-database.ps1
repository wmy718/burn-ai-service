param(
    [string]$DbHost, [int]$DbPort = 3306, [string]$DbName,
    [string]$DbUser, [Security.SecureString]$Password, [string]$MysqlPath
)
$ErrorActionPreference = 'Stop'
$tables = @('llm_disease','llm_indicator','llm_indicator_value_option','llm_indicator_group','llm_indicator_group_item','llm_decision_tree','llm_decision_node','llm_decision_node_edge','llm_decision_tree_dependency','llm_decision_tree_dependency_condition','llm_decision_tree_indicator')
$tempConfig = $null
function Escape-Ini([string]$value) {
    if ($value -match '[\r\n]') { throw 'Connection values must not contain line breaks.' }
    return '"' + $value.Replace('\','\\').Replace('"','\"') + '"'
}
function Invoke-Mysql([string]$sql, [string]$inputFile) {
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $script:mysqlExe
    $startInfo.Arguments = '--defaults-extra-file="' + $script:tempConfig + '" --default-character-set=utf8mb4 --batch --skip-column-names'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $p = New-Object Diagnostics.Process
    $p.StartInfo = $startInfo
    try {
        [void]$p.Start()
        $outputTask = $p.StandardOutput.ReadToEndAsync()
        $errorTask = $p.StandardError.ReadToEndAsync()
        $prefixBytes = [Text.Encoding]::UTF8.GetBytes($sql + "`n")
        $p.StandardInput.BaseStream.Write($prefixBytes, 0, $prefixBytes.Length)
        if ($inputFile) {
            $inputStream = [IO.File]::OpenRead($inputFile)
            try { $inputStream.CopyTo($p.StandardInput.BaseStream) } finally { $inputStream.Dispose() }
        }
        $p.StandardInput.Close()
        $p.WaitForExit()
        $outputText = $outputTask.Result
        $errorText = $errorTask.Result
        if ($p.ExitCode -ne 0) { throw ('MySQL failed: ' + $errorText.Trim()) }
        return $outputText.Trim()
    } finally { $p.Dispose() }
}
try {
    if (-not $DbHost) { $DbHost = Read-Host 'MySQL host [127.0.0.1]'; if (-not $DbHost) { $DbHost = '127.0.0.1' } }
    if (-not $DbName) { $DbName = Read-Host 'Database name [burn_treatment]'; if (-not $DbName) { $DbName = 'burn_treatment' } }
    if ($DbName -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') { throw 'Database name must begin with a letter and contain only letters, numbers and underscores.' }
    if (-not $DbUser) { $DbUser = Read-Host 'MySQL username [root]'; if (-not $DbUser) { $DbUser = 'root' } }
    if (-not $MysqlPath) {
        $mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
        if ($mysqlCommand) { $MysqlPath = $mysqlCommand.Source }
        else {
            $found = Get-ChildItem 'C:\Program Files\MySQL\*\bin\mysql.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) { $MysqlPath = $found.FullName } else { $MysqlPath = Read-Host 'Full path to mysql.exe' }
        }
    }
    $script:mysqlExe = (Get-Item -LiteralPath $MysqlPath).FullName
    if (-not $Password) { $Password = Read-Host 'MySQL password (hidden)' -AsSecureString }
    $tempConfig = Join-Path $env:TEMP ('burn-ai-mysql-' + [Guid]::NewGuid().ToString('N') + '.cnf')
    [IO.File]::WriteAllText($tempConfig, '', [Text.UTF8Encoding]::new($false))
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath $tempConfig -AclObject $acl
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
    try {
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        $config = "[client]`nhost=$(Escape-Ini $DbHost)`nport=$DbPort`nuser=$(Escape-Ini $DbUser)`npassword=$(Escape-Ini $plainPassword)`n"
        [IO.File]::WriteAllText($tempConfig, $config, [Text.UTF8Encoding]::new($false))
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr); $plainPassword = $null; $config = $null }
    $quotedTables = ($tables | ForEach-Object { "'$_'" }) -join ','
    $existing = Invoke-Mysql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DbName' AND TABLE_NAME IN ($quotedTables);" ''
    if ($existing) { throw "Import stopped: target database already contains these configuration tables. Use a new database name. No tables were changed.`n$existing" }
    [void](Invoke-Mysql "CREATE DATABASE IF NOT EXISTS $DbName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" '')
    Write-Host "[INFO] Importing 11 configuration tables into $DbName..."
    [void](Invoke-Mysql "USE $DbName;" (Join-Path $PSScriptRoot 'burn_ai_config.sql'))
    $counts = Invoke-Mysql "USE $DbName; SELECT code, name FROM llm_disease; SELECT disease_id,COUNT(*) FROM llm_indicator GROUP BY disease_id; SELECT disease_id,COUNT(*) FROM llm_decision_tree GROUP BY disease_id;" ''
    Write-Host '[OK] Import completed. Disease, indicator and decision tree counts:'
    Write-Host $counts
    Write-Host "[INFO] Set BURN_AI_DB_URL to use database $DbName, and configure its username/password before starting Java."
    exit 0
} catch { Write-Host ('[ERROR] ' + $_.Exception.Message) -ForegroundColor Red; exit 1 }
finally { if ($tempConfig -and (Test-Path -LiteralPath $tempConfig)) { Remove-Item -LiteralPath $tempConfig -Force } }
