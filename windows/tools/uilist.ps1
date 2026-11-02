param([int]$ProcId, [string]$WindowMatch = '', [string]$List = 'edit')
# Lists controls of a given type with their names / values, for driving the app via UI Automation.
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = [System.Windows.Automation.AutomationElement]::RootElement
$pidCond = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcId)

$typeMap = @{ edit = 'Edit'; button = 'Button'; text = 'Text'; combo = 'ComboBox'; check = 'CheckBox'; tab = 'TabItem' }
$type = [System.Windows.Automation.ControlType]::$($typeMap[$List])

foreach ($w in $root.FindAll([System.Windows.Automation.TreeScope]::Children, $pidCond)) {
    if ($WindowMatch -and $w.Current.Name -notmatch $WindowMatch) { continue }
    $tc = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty, $type)
    foreach ($el in $w.FindAll([System.Windows.Automation.TreeScope]::Descendants, $tc)) {
        $val = ''
        try { $val = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value } catch {}
        $r = $el.Current.BoundingRectangle
        Write-Output ("[{0}] name={1} value={2} @{3},{4}" -f $w.Current.Name, $el.Current.Name, $val, [int]$r.X, [int]$r.Y)
    }
}
