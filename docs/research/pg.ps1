param([string[]]$Urls, [string]$Pat = '智能体|Agent|AI 助理|AI助理|大模型|Joule|Copilot', [int]$Max = 12)
$ProgressPreference = 'SilentlyContinue'
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36'
foreach ($u in $Urls) {
  "#### $u"
  try {
    $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 35 -Headers @{'User-Agent' = $UA}
    $t = $r.Content -replace '(?s)<script.*?</script>', ' ' -replace '(?s)<style.*?</style>', ' ' -replace '<[^>]+>', "`n" -replace '&nbsp;', ' '
    $lines = ($t -split "`n") | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 6 }
    $hit = $lines | Where-Object { $_ -match $Pat } | Select-Object -Unique -First $Max
    if (-not $hit) { "  (no keyword hit; len=$($r.Content.Length))" } else { $hit | ForEach-Object { "  " + ($_ -replace '\s+', ' ') } }
  } catch { "  ERR $($_.Exception.Message)" }
  ""
}
