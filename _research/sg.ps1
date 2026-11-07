param([string[]]$Queries, [int]$Top = 6, [int]$Len = 320)
$ProgressPreference = 'SilentlyContinue'
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36'
foreach ($q in $Queries) {
  "#### $q"
  $u = 'https://www.sogou.com/web?query=' + [uri]::EscapeDataString($q)
  try {
    $h = (Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 35 -Headers @{'User-Agent' = $UA}).Content
  } catch { "  ERR $($_.Exception.Message)"; continue }
  $parts = [regex]::Split($h, '<h3 class="vr-title')
  $n = 0
  for ($i = 1; $i -lt $parts.Length; $i++) {
    if ($n -ge $Top) { break }
    $b = $parts[$i]
    if ($b.Length -gt 6000) { $b = $b.Substring(0, 6000) }
    $title = ''
    if ($b -match '(?s)^[^>]*>(.*?)</h3>') { $title = ($matches[1] -replace '<[^>]+>' -replace '\s+', ' ').Trim() }
    if ($title.Length -lt 4) { continue }
    $real = ''
    if ($b -match 'data-url="(https?://[^"]+)"') { $real = $matches[1] }
    $date = ''
    if ($b -match '>\s*(20\d\d-\d\d-\d\d)\s*<') { $date = $matches[1] }
    $dom = ''
    if ($b -match '<span>([^<]{4,40})</span>') { $dom = $matches[1] }
    $snip = ($b -replace '(?s)<script.*?</script>', '' -replace '<[^>]+>', ' ' -replace '&nbsp;', ' ' -replace '\s+', ' ').Trim()
    if ($snip.Length -gt $Len) { $snip = $snip.Substring(0, $Len) }
    $n++
    "  [$n] $title  {$date | $dom}"
    if ($real) { "      URL: $real" }
    "      $snip"
  }
  ""
  Start-Sleep -Milliseconds 500
}

