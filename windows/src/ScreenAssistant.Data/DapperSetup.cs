using System.Data;
using System.Globalization;
using Dapper;

namespace ScreenAssistant.Data;

/// <summary>SQLite 以 TEXT 存时间，Dapper 需要显式类型处理。</summary>
public sealed class SqliteDateTimeOffsetHandler : SqlMapper.TypeHandler<DateTimeOffset>
{
    public override void SetValue(IDbDataParameter parameter, DateTimeOffset value)
    {
        parameter.DbType = DbType.String;
        parameter.Value = value.ToString("O", CultureInfo.InvariantCulture);
    }

    public override DateTimeOffset Parse(object value) => value switch
    {
        string s => DateTimeOffset.Parse(s, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind),
        DateTime dt => new DateTimeOffset(DateTime.SpecifyKind(dt, DateTimeKind.Local)),
        _ => DateTimeOffset.Parse(value.ToString() ?? "", CultureInfo.InvariantCulture)
    };
}

public static class DapperSetup
{
    private static bool _done;
    private static readonly object Gate = new();

    public static void Register()
    {
        lock (Gate)
        {
            if (_done) return;
            SqlMapper.AddTypeHandler(new SqliteDateTimeOffsetHandler());
            SqlMapper.AddTypeHandler(new SqliteNullableDateTimeOffsetHandler());
            _done = true;
        }
    }
}

public sealed class SqliteNullableDateTimeOffsetHandler : SqlMapper.TypeHandler<DateTimeOffset?>
{
    public override void SetValue(IDbDataParameter parameter, DateTimeOffset? value)
    {
        parameter.DbType = DbType.String;
        parameter.Value = value?.ToString("O", CultureInfo.InvariantCulture) ?? (object)DBNull.Value;
    }

    public override DateTimeOffset? Parse(object value) =>
        value is null or DBNull ? null : new SqliteDateTimeOffsetHandler().Parse(value);
}
