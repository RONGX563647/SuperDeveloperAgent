param([string[]]$Queries, [int]$Top = 8)
$ProgressPreference = 'SilentlyContinue'
$out = @()
foreach ($q in $Queries) {
  $u = "https://cn.bing.com/search?q=" + [uri]::EscapeDataString($q) + "&format=rss&count=20"
  try {
    $raw = (Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 40).Content
    if ($raw -notmatch '<rss') { $out += "### $q`n  [no rss]`n"; continue }
    $raw = $raw -replace '<!DOCTYPE[^>]*>', ''
    [xml]$x = $raw
    $out += "### $q"
    $i = 0
    foreach ($it in $x.rss.channel.item) {
      if ($i -ge $Top) { break }
      $i++
      $d = ($it.description -replace '<[^>]+>', '' -replace '\s+', ' ')
      if ($d.Length -gt 260) { $d = $d.Substring(0, 260) }
      $out += ("- " + $it.title + "`n  " + $it.link + "`n  " + $it.pubDate + "`n  " + $d)
    }
    $out += ""
  } catch { $out += "### $q`n  [ERR] $($_.Exception.Message.Substring(0,[Math]::Min(80,$_.Exception.Message.Length)))`n" }
  Start-Sleep -Milliseconds 400
}
$out -join "`n"
