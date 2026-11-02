param([int]$ProcId, [string]$Name)
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)
foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)) {
    $c = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, $Name)
    $el = $w.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $c)
    if ($el) {
        $p = $el.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
        $p.Select()
        Write-Output "SELECTED '$Name' in '$($w.Current.Name)'"
        exit 0
    }
}
Write-Output "NOT_FOUND '$Name'"
