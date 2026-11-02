param([int]$ProcId, [string]$Name, [string]$WindowMatch = '')
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)
foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)) {
    if ($WindowMatch -and $w.Current.Name -notmatch $WindowMatch) { continue }
    $bc = New-Object System.Windows.Automation.AndCondition(
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::Button)),
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $Name)))
    $b = $w.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $bc)
    if ($b) {
        $b.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
        Write-Output "INVOKED '$Name' in '$($w.Current.Name)'"
        exit 0
    }
}
Write-Output "NOT_FOUND '$Name'"
