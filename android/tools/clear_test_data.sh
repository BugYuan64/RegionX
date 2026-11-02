# 测试数据清理：清空会话、任务、消息与截图，保留模型配置和设置
# 用法：adb shell "run-as com.regionx.app.debug sh /data/local/tmp/clear_test_data.sh"
DB=files/screenassistant.db
rm -f files/images/*.png 2>/dev/null
sqlite3 $DB "DELETE FROM messages; DELETE FROM tasks; DELETE FROM sessions;"
sqlite3 $DB "VACUUM;"
echo "cleared"
