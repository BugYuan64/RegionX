param([int]$ProcId, [int]$Index)
# 展开第 N 个 ComboBox（ExpandCollapsePattern），并列出弹出的选项。
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = [System.Windows.Automation.AutomationElement]::RootElement
$pidCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)
$comboCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::ComboBox)

$combos = @()
foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $pidCond)) {
    foreach ($c in $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, $comboCond)) { $combos += $c }
}
if ($Index -ge $combos.Count) { Write-Output "ONLY $($combos.Count) combos"; exit 1 }

$combo = $combos[$Index]
$combo.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern).Expand()
Start-Sleep -Milliseconds 600
Write-Output "EXPANDED [$Index]"

$itemCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::ListItem)
foreach ($i in $combo.FindAll([System.Windows.Automation.TreeScope]::Descendants, $itemCond)) {
    Write-Output ("  item: " + $i.Current.Name)
}
