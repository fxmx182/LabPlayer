namespace LibertyX.Partition.Core.Operations;

/// <summary>Por onde uma operação longa conversa com a tela: progresso, log, pausa e cancelamento.</summary>
public sealed class OpSink
{
    private readonly CancellationTokenSource _cts = new();
    public CancellationToken Token => _cts.Token;
    public ManualResetEventSlim PauseGate { get; } = new(true);
    public event Action<string>? LogLine;
    public event Action<double, string?, string?>? ProgressChanged;

    public void Log(string line) => LogLine?.Invoke(line);
    /// <param name="fraction">0..1, ou negativo para indeterminado</param>
    public void Progress(double fraction, string? status = null, string? detail = null) => ProgressChanged?.Invoke(fraction, status, detail);
    public void Cancel() => _cts.Cancel();
    public bool Paused => !PauseGate.IsSet;
    public void Pause() => PauseGate.Reset();
    public void Resume() => PauseGate.Set();
}
