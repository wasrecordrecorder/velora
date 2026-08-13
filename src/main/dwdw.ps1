$totalLines = 0
$totalFiles = 0

Get-ChildItem -Path . -Recurse -Filter "*.java" -File | ForEach-Object {
    $lines = 0

    foreach ($line in [System.IO.File]::ReadLines($_.FullName)) {
        $lines++
    }

    $totalLines += $lines
    $totalFiles++

    Write-Host "$($_.FullName): $lines"
}

Write-Host ""
Write-Host "Java files: $totalFiles"
Write-Host "Total lines: $totalLines"