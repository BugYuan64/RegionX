param([int]$ProcId, [int]$Index, [string]$Value, [string]$WindowMatch = '')
# Sets the ValuePattern text of the N-th Edit control in matching windows.
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = [System.Windows.Automation.AutomationElement]::RootElement
$pidCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)
$editCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Edit)

$all = @()
foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $pidCond)) {
    if ($WindowMatch -and $w.Current.Name -notmatch $WindowMatch) { continue }
    foreach ($el in $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, $editCond)) {
        $all += $el
    }
}

if ($Index -ge $all.Count) { Write-Output "ONLY $($all.Count) edits"; exit 1 }
$all[$Index].GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue($Value)
Write-Output "SET [$Index] = $Value"
