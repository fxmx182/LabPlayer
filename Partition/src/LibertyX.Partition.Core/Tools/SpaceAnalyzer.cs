using System.IO.Enumeration;

namespace LibertyX.Partition.Core.Tools;

public sealed class SpaceNode
{
    public string Name { get; init; } = "";
    public string FullPath { get; init; } = "";
    public bool IsDir { get; init; }
    public long Size { get; set; }
    public long Files { get; set; }
    public DateTime Modified { get; init; }
    public SpaceNode? Parent { get; set; }
    public List<SpaceNode>? Children { get; set; }
    public bool Denied { get; set; }
    public string Extension => IsDir ? "" : Path.GetExtension(Name).TrimStart('.').ToLowerInvariant();
}

public readonly record struct SpaceProgress(long Files, long Bytes, string Current);

/// <summary>Varre uma pasta (ou unidade inteira) em paralelo e monta a árvore de tamanhos.</summary>
public static class SpaceAnalyzer
{
    public static SpaceNode Analyze(string root, IProgress<SpaceProgress>? progress, CancellationToken ct)
    {
        var top = new SpaceNode { Name = root, FullPath = root, IsDir = true };
        long files = 0, bytes = 0;
        var opts = new EnumerationOptions
        {
            IgnoreInaccessible = true, RecurseSubdirectories = false, AttributesToSkip = FileAttributes.ReparsePoint,
            ReturnSpecialDirectories = false,
        };
        void Walk(SpaceNode dir, int depth)
        {
            ct.ThrowIfCancellationRequested();
            var kids = new List<SpaceNode>();
            try
            {
                var en = new FileSystemEnumerable<SpaceNode>(dir.FullPath,
                    (ref FileSystemEntry e) => new SpaceNode
                    {
                        Name = e.FileName.ToString(), FullPath = e.ToFullPath(), IsDir = e.IsDirectory,
                        Size = e.IsDirectory ? 0 : e.Length, Modified = e.LastWriteTimeUtc.LocalDateTime, Files = e.IsDirectory ? 0 : 1,
                    }, opts);
                kids.AddRange(en);
            }
            catch (UnauthorizedAccessException) { dir.Denied = true; }
            catch (IOException) { dir.Denied = true; }
            foreach (var k in kids) k.Parent = dir;
            var subdirs = kids.Where(k => k.IsDir).ToList();
            if (depth < 3 && subdirs.Count > 1)
                Parallel.ForEach(subdirs, new ParallelOptions { CancellationToken = ct, MaxDegreeOfParallelism = 8 }, d => Walk(d, depth + 1));
            else
                foreach (var d in subdirs) Walk(d, depth + 1);
            long f = 0;
            foreach (var k in kids.Where(k => !k.IsDir)) f++;
            Interlocked.Add(ref files, f);
            long own = kids.Where(k => !k.IsDir).Sum(k => k.Size);
            Interlocked.Add(ref bytes, own);
            dir.Size = kids.Sum(k => k.Size);
            dir.Files = kids.Sum(k => k.Files);
            dir.Children = kids.OrderByDescending(k => k.Size).ToList();
            if (Interlocked.Read(ref files) % 2000 < f + 1) progress?.Report(new(Interlocked.Read(ref files), Interlocked.Read(ref bytes), dir.FullPath));
        }
        Walk(top, 0);
        progress?.Report(new(files, bytes, ""));
        return top;
    }

    public static IEnumerable<SpaceNode> AllFiles(SpaceNode n)
    {
        var st = new Stack<SpaceNode>();
        st.Push(n);
        while (st.Count > 0)
        {
            var x = st.Pop();
            if (!x.IsDir) { yield return x; continue; }
            if (x.Children != null) foreach (var c in x.Children) st.Push(c);
        }
    }

    public static void Remove(SpaceNode n)
    {
        var p = n.Parent;
        if (p?.Children == null) return;
        p.Children.Remove(n);
        for (var a = p; a != null; a = a.Parent) { a.Size -= n.Size; a.Files -= n.IsDir ? n.Files : 1; }
    }
}

/// <summary>Treemap "squarified" (Bruls et al.): retângulos o mais quadrados possível.</summary>
public static class Treemap
{
    public readonly record struct Rect(double X, double Y, double W, double H);

    public static List<(T item, Rect rect)> Layout<T>(IReadOnlyList<(T item, double weight)> items, Rect area)
    {
        var res = new List<(T, Rect)>();
        var list = items.Where(i => i.weight > 0).OrderByDescending(i => i.weight).ToList();
        double total = list.Sum(i => i.weight);
        if (total <= 0 || area.W <= 0 || area.H <= 0) return res;
        double scale = area.W * area.H / total;
        var scaled = list.Select(i => (i.item, a: i.weight * scale)).ToList();
        Squarify(scaled, 0, area, res);
        return res;
    }

    private static double Worst(List<double> row, double side)
    {
        double s = row.Sum(), mx = row.Max(), mn = row.Min();
        double s2 = s * s, w2 = side * side;
        return Math.Max(w2 * mx / s2, s2 / (w2 * mn));
    }

    private static void Squarify<T>(List<(T item, double a)> items, int start, Rect r, List<(T, Rect)> res)
    {
        while (start < items.Count)
        {
            double side = Math.Min(r.W, r.H);
            var row = new List<double> { items[start].a };
            int end = start + 1;
            while (end < items.Count)
            {
                var next = new List<double>(row) { items[end].a };
                if (Worst(next, side) > Worst(row, side)) break;
                row = next; end++;
            }
            double sum = row.Sum();
            if (r.W >= r.H)
            {
                double w = sum / r.H, y = r.Y;
                for (int i = start; i < end; i++) { double h = items[i].a / w; res.Add((items[i].item, new Rect(r.X, y, w, h))); y += h; }
                r = new Rect(r.X + w, r.Y, Math.Max(0, r.W - w), r.H);
            }
            else
            {
                double h = sum / r.W, x = r.X;
                for (int i = start; i < end; i++) { double w = items[i].a / h; res.Add((items[i].item, new Rect(x, r.Y, w, h))); x += w; }
                r = new Rect(r.X, r.Y + h, r.W, Math.Max(0, r.H - h));
            }
            start = end;
        }
    }
}
