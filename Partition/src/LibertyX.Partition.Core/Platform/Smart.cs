using static LibertyX.Partition.Core.IO.Bin;

namespace LibertyX.Partition.Core.Platform;

public enum HealthLevel { Unknown, Good, Caution, Bad }

public sealed record SmartAttribute(int Id, string Name, int Current, int Worst, int Threshold, long Raw, bool Critical, HealthLevel Level);

public sealed class SmartReport
{
    public HealthLevel Health { get; set; } = HealthLevel.Unknown;
    public string Summary { get; set; } = "S.M.A.R.T. indisponível neste disco (comum em adaptadores USB).";
    public int? TemperatureC { get; set; }
    public long? PowerOnHours { get; set; }
    public long? PowerCycles { get; set; }
    public int? LifeRemainingPercent { get; set; }
    public long? HostBytesWritten { get; set; }
    public long? HostBytesRead { get; set; }
    public long? MediaErrors { get; set; }
    public long? UnsafeShutdowns { get; set; }
    public List<SmartAttribute> Attributes { get; } = [];
    public string Source { get; set; } = "";
}

/// <summary>Interpretação dos dados S.M.A.R.T. (ATA) e do log de saúde do NVMe.</summary>
public static class SmartParser
{
    private static readonly Dictionary<int, (string name, bool critical)> Names = new()
    {
        [1] = ("Taxa de erros de leitura", false), [3] = ("Tempo para girar", false), [4] = ("Partidas/paradas", false),
        [5] = ("Setores realocados", true), [7] = ("Taxa de erros de busca", false), [9] = ("Horas ligado", false),
        [10] = ("Tentativas de giro", true), [12] = ("Ciclos de energia", false), [170] = ("Blocos reserva disponíveis", false),
        [171] = ("Falhas de programação", true), [172] = ("Falhas de apagamento", true), [173] = ("Desgaste médio", false),
        [174] = ("Desligamentos inesperados", false), [177] = ("Contagem de desgaste", false), [179] = ("Blocos reserva usados", false),
        [181] = ("Falhas de programação", true), [182] = ("Falhas de apagamento", true), [183] = ("Erros de downshift SATA", false),
        [184] = ("Erros fim a fim", true), [187] = ("Erros incorrigíveis reportados", true), [188] = ("Comandos expirados", false),
        [189] = ("Escritas com a cabeça alta", false), [190] = ("Temperatura do fluxo de ar", false), [191] = ("Impactos (G-sense)", false),
        [192] = ("Desligamentos de emergência", false), [193] = ("Ciclos de carga", false), [194] = ("Temperatura", false),
        [195] = ("ECC recuperados", false), [196] = ("Eventos de realocação", true), [197] = ("Setores pendentes", true),
        [198] = ("Setores incorrigíveis", true), [199] = ("Erros de CRC no cabo", false), [200] = ("Erros de escrita", false),
        [202] = ("Vida útil usada", false), [220] = ("Deslocamento do disco", false), [222] = ("Horas carregado", false),
        [226] = ("Tempo de carga", false), [230] = ("Amplitude da cabeça", false), [231] = ("Vida útil restante", false),
        [232] = ("Reserva disponível", false), [233] = ("Desgaste da mídia", false), [234] = ("Média de apagamentos", false),
        [235] = ("Blocos bons", false), [240] = ("Horas de voo da cabeça", false), [241] = ("Total escrito (LBAs)", false),
        [242] = ("Total lido (LBAs)", false), [249] = ("Escritas NAND (GiB)", false),
    };

    public static SmartReport ParseAta(byte[] values, byte[]? thresholds)
    {
        var r = new SmartReport { Source = "ATA S.M.A.R.T." };
        var thr = new Dictionary<int, int>();
        if (thresholds != null)
            for (int i = 0; i < 30; i++) { int o = 2 + i * 12; if (o + 2 <= thresholds.Length && thresholds[o] != 0) thr[thresholds[o]] = thresholds[o + 1]; }
        bool failing = false, caution = false;
        for (int i = 0; i < 30; i++)
        {
            int o = 2 + i * 12;
            if (o + 12 > values.Length) break;
            int id = values[o];
            if (id == 0) continue;
            int cur = values[o + 3], worst = values[o + 4];
            long raw = 0;
            for (int b = 0; b < 6; b++) raw |= (long)values[o + 5 + b] << (8 * b);
            thr.TryGetValue(id, out int t);
            var (name, critical) = Names.TryGetValue(id, out var n) ? n : ($"Atributo {id}", false);
            var level = HealthLevel.Good;
            if (t > 0 && cur <= t) { level = HealthLevel.Bad; failing = true; }
            else if (critical && (raw & 0xFFFFFFFF) > 0) { level = HealthLevel.Caution; caution = true; }
            r.Attributes.Add(new SmartAttribute(id, name, cur, worst, t, raw, critical, level));
            switch (id)
            {
                case 9: r.PowerOnHours = raw & 0xFFFFFFFF; break;
                case 12: r.PowerCycles = raw & 0xFFFFFFFF; break;
                case 194 or 190 when r.TemperatureC == null: { int c = (int)(raw & 0xFF); if (c is > 0 and < 120) r.TemperatureC = c; break; }
                case 231 or 202 or 233 when r.LifeRemainingPercent == null: if (cur is > 0 and <= 100) r.LifeRemainingPercent = cur; break;
                case 241: r.HostBytesWritten = (raw & 0xFFFFFFFFFFFF) * 512; break;
                case 242: r.HostBytesRead = (raw & 0xFFFFFFFFFFFF) * 512; break;
            }
        }
        r.Health = failing ? HealthLevel.Bad : caution ? HealthLevel.Caution : r.Attributes.Count > 0 ? HealthLevel.Good : HealthLevel.Unknown;
        long realloc = r.Attributes.FirstOrDefault(a => a.Id == 5)?.Raw & 0xFFFFFFFF ?? 0;
        long pending = r.Attributes.FirstOrDefault(a => a.Id == 197)?.Raw & 0xFFFFFFFF ?? 0;
        r.Summary = r.Health switch
        {
            HealthLevel.Bad => "Falha prevista pelo próprio disco. Copie os dados agora (Backup → imagem de disco) antes de qualquer outra coisa.",
            HealthLevel.Caution => $"Atenção: {realloc} setores realocados, {pending} pendentes. O disco está gastando a reserva — faça uma imagem dele.",
            HealthLevel.Good => "Saudável: nenhum indicador crítico.",
            _ => r.Summary,
        };
        return r;
    }

    public static SmartReport ParseNvme(byte[] log)
    {
        var r = new SmartReport { Source = "Log de saúde NVMe" };
        if (log.Length < 512) return r;
        int crit = log[0];
        int kelvin = U16(log, 1);
        if (kelvin > 200) r.TemperatureC = kelvin - 273;
        int spare = log[3], spareThr = log[4], used = log[5];
        r.LifeRemainingPercent = Math.Max(0, 100 - used);
        static long U128Low(byte[] b, int o) => (long)Math.Min(U64(b, o), long.MaxValue / 1000);
        r.HostBytesRead = U128Low(log, 32) * 512_000;
        r.HostBytesWritten = U128Low(log, 48) * 512_000;
        r.PowerCycles = U128Low(log, 112);
        r.PowerOnHours = U128Low(log, 128);
        r.UnsafeShutdowns = U128Low(log, 144);
        r.MediaErrors = U128Low(log, 160);
        long errLog = U128Low(log, 176);

        void Add(int id, string name, long raw, bool critical, HealthLevel lv) => r.Attributes.Add(new SmartAttribute(id, name, 0, 0, 0, raw, critical, lv));
        Add(1, "Aviso crítico (bits)", crit, true, crit != 0 ? HealthLevel.Bad : HealthLevel.Good);
        Add(2, "Temperatura (°C)", r.TemperatureC ?? 0, false, r.TemperatureC > 70 ? HealthLevel.Caution : HealthLevel.Good);
        Add(3, "Reserva disponível (%)", spare, true, spare < spareThr ? HealthLevel.Bad : HealthLevel.Good);
        Add(4, "Limite da reserva (%)", spareThr, false, HealthLevel.Good);
        Add(5, "Vida útil usada (%)", used, false, used >= 100 ? HealthLevel.Bad : used >= 90 ? HealthLevel.Caution : HealthLevel.Good);
        Add(6, "Dados lidos (GB)", r.HostBytesRead.Value / 1_000_000_000, false, HealthLevel.Good);
        Add(7, "Dados escritos (GB)", r.HostBytesWritten.Value / 1_000_000_000, false, HealthLevel.Good);
        Add(8, "Ciclos de energia", r.PowerCycles.Value, false, HealthLevel.Good);
        Add(9, "Horas ligado", r.PowerOnHours.Value, false, HealthLevel.Good);
        Add(10, "Desligamentos inesperados", r.UnsafeShutdowns.Value, false, HealthLevel.Good);
        Add(11, "Erros de mídia/integridade", r.MediaErrors.Value, true, r.MediaErrors > 0 ? HealthLevel.Caution : HealthLevel.Good);
        Add(12, "Entradas no log de erros", errLog, false, HealthLevel.Good);

        r.Health = crit != 0 || spare < spareThr || used >= 100 ? HealthLevel.Bad
                 : r.MediaErrors > 0 || used >= 90 ? HealthLevel.Caution : HealthLevel.Good;
        r.Summary = r.Health switch
        {
            HealthLevel.Bad => "O SSD reporta estado crítico. Faça uma imagem dele já — SSD costuma morrer de uma vez.",
            HealthLevel.Caution => r.MediaErrors > 0 ? $"{r.MediaErrors} erros de integridade de dados registrados. Vale fazer backup." : $"Vida útil quase no fim ({used}% usado).",
            _ => $"Saudável — {r.LifeRemainingPercent}% de vida útil restante.",
        };
        return r;
    }
}
