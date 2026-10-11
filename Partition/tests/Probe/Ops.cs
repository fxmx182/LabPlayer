using LibertyX.Partition.Core.Tools;
using System.Security.Cryptography;
using LibertyX.Partition.Core.FileSystems;
using LibertyX.Partition.Core.IO;
using LibertyX.Partition.Core.Operations;
using LibertyX.Partition.Core.Partitioning;
using LibertyX.Partition.Core.Platform;
using LibertyX.Partition.Core.Recovery;

/// <summary>Testes das operações que escrevem no disco, sempre em cópias das imagens.</summary>
public static class Ops
{
    static int fails;
    static void Check(bool ok, string what) { Console.WriteLine((ok ? "  OK   " : "  FALHA ") + what); if (!ok) fails++; }

    static DiskInfo Load(string path)
    {
        var d = new DiskInfo { Number = 200, IsImage = true, ImagePath = path, DevicePath = path, Model = Path.GetFileName(path), ReadOnly = false };
        using var dev = new ImageFileDevice(path);
        d.Size = dev.Length;
        LayoutBuilder.Build(d, dev);
        return d;
    }

    static string Copy(string src, string name)
    {
        var p = Path.Combine(Path.GetTempPath(), name);
        File.Copy(src, p, true);
        return p;
    }

    /// <summary>Hashes dos arquivos (vivos e apagados) de cada partição legível.</summary>
    static Dictionary<string, string> Fingerprint(DiskInfo d, bool liveOnly = false)
    {
        var res = new Dictionary<string, string>();
        using var dev = new ImageFileDevice(d.ImagePath!);
        foreach (var p in d.Partitions.Where(p => FsDetect.CanRead(p.FileSystem)))
        {
            var r = FileSystemScanner.Scan(dev.Slice(p.Offset, p.Length), new ScanOptions { IncludeLive = true }, null, CancellationToken.None);
            foreach (var f in r.Items.Where(i => !i.IsDirectory && i.Data != null && i.Size < 8 << 20 && (!liveOnly || i.Origin == ItemOrigin.Live)))
            {
                var b = new byte[f.Size];
                f.Data!.Read(0, b);
                res[$"{FsDetect.Name(p.FileSystem)}:{f.FullPath.Split('\\', 2).Last()}"] = Convert.ToHexStringLower(SHA256.HashData(b));
            }
        }
        return res;
    }

    static bool SameFiles(Dictionary<string, string> a, Dictionary<string, string> b, out string diff)
    {
        var missing = a.Where(kv => !b.TryGetValue(kv.Key, out var h) || h != kv.Value).Select(kv => kv.Key).ToList();
        diff = string.Join(", ", missing.Take(5));
        return missing.Count == 0 && a.Count > 0;
    }

    public static async Task<int> Run(string dir)
    {
        var sink = new OpSink();
        string orig = Path.Combine(dir, "disk.img");
        var baseline = Fingerprint(Load(orig));
        Console.WriteLine($"\nOperações (impressão digital: {baseline.Count} arquivos)");

        // 1. converter GPT -> MBR -> GPT
        {
            var p = Copy(orig, "lx-conv.img");
            var d = Load(p);
            DiskOperations.ConvertStyle(d, TableStyle.Mbr, sink);
            d = Load(p);
            Check(d.Table.Style == TableStyle.Mbr && d.Table.Entries.Count == 3, "GPT → MBR");
            Check(SameFiles(baseline, Fingerprint(d), out var df), "dados intactos após GPT → MBR " + df);
            DiskOperations.ConvertStyle(d, TableStyle.Gpt, sink);
            d = Load(p);
            Check(d.Table.Style == TableStyle.Gpt && d.Table.Entries.Count == 3 && d.Table.Warnings.Count == 0, "MBR → GPT sem avisos");
            Check(d.Partitions.First(x => x.Entry != null).IsEsp, "ESP preservada na ida e volta");
            Check(SameFiles(baseline, Fingerprint(d), out df), "dados intactos após MBR → GPT " + df);
            File.Delete(p);
        }

        // 2. mover partição (direita e esquerda) — exFAT é a última; NTFS tem espaço? cria espaço apagando a exFAT
        {
            var p = Copy(orig, "lx-move.img");
            var d = Load(p);
            var ex = d.Partitions.First(x => x.FileSystem == FsKind.ExFat);
            var exKeys = baseline.Where(kv => kv.Key.StartsWith("exFAT")).ToDictionary();
            // move a exFAT 1 MiB para a esquerda não dá (NTFS colado); remove a NTFS da tabela e move a exFAT para trás dela
            var t = d.Table.Clone();
            var ntfsEntry = t.Entries.First(e => e.StartLba == d.Partitions.First(x => x.FileSystem == FsKind.Ntfs).StartLba);
            long ntfsStart = ntfsEntry.StartLba;
            t.Entries.Remove(ntfsEntry);
            DiskOperations.WriteTable(d, t, "teste", sink);
            d = Load(p);
            ex = d.Partitions.First(x => x.FileSystem == FsKind.ExFat);
            DiskOperations.MovePartition(ex, ntfsStart + 4096, sink);   // para a esquerda, com sobreposição
            d = Load(p);
            var moved = d.Partitions.First(x => x.FileSystem == FsKind.ExFat);
            Check(moved.StartLba == ntfsStart + 4096, "exFAT movida para a esquerda");
            Check(SameFiles(exKeys, Fingerprint(d), out var df), "conteúdo da exFAT intacto após mover para a esquerda " + df);
            DiskOperations.MovePartition(moved, moved.StartLba + 2048 * 3 + 7, sink);   // para a direita, sobreposta e desalinhada
            d = Load(p);
            moved = d.Partitions.First(x => x.FileSystem == FsKind.ExFat);
            Check(SameFiles(exKeys, Fingerprint(d), out df), "conteúdo da exFAT intacto após mover para a direita " + df);
            Check(!DiskOperations.IsAligned(moved), "partição desalinhada de propósito");
            var target = DiskOperations.AlignedTarget(moved);
            Check(target != null && target.Value % 2048 == 0, "alvo de alinhamento calculado");
            DiskOperations.MovePartition(moved, target!.Value, sink);
            d = Load(p);
            moved = d.Partitions.First(x => x.FileSystem == FsKind.ExFat);
            Check(DiskOperations.IsAligned(moved) && SameFiles(exKeys, Fingerprint(d), out df), "alinhada e intacta " + df);
            using (var dev = new ImageFileDevice(p))
            {
                var b = dev.ReadBytes(moved.Offset, 512);
                Check((long)Bin.U64(b, 64) == moved.StartLba, "exFAT: deslocamento da partição atualizado no boot (com soma da região)");
                Check(ExFatVolume.Open(dev.Slice(moved.Offset, moved.Length), out _) != null, "exFAT abre depois de reescrever a região de boot");
            }
            File.Delete(p);
        }

        // 3. número de série
        {
            var p = Copy(orig, "lx-serial.img");
            var d = Load(p);
            foreach (var part in d.Partitions.Where(x => FsDetect.CanRead(x.FileSystem)))
            {
                ulong s = part.FileSystem == FsKind.Ntfs ? 0x1122334455667788UL : 0xA1B2C3D4UL;
                DiskOperations.ChangeSerial(part, s, sink);
                using var dev = new ImageFileDevice(p);
                var v = dev.Slice(part.Offset, part.Length);
                Check(BootSector.ReadSerial(v, part.FileSystem) == s && FsDetect.Detect(v) == part.FileSystem, $"série trocada em {FsDetect.Name(part.FileSystem)}");
            }
            Check(SameFiles(baseline, Fingerprint(Load(p)), out var df), "dados intactos após trocar séries " + df);
            File.Delete(p);
        }

        // 4. clonar disco para um maior, crescendo a última
        {
            var src = Load(orig);
            var tp = Path.Combine(Path.GetTempPath(), "lx-clone.img");
            using (var f = new FileStream(tp, FileMode.Create)) f.SetLength(400L << 20);
            var target = Load(tp);
            var plan = DiskOperations.PlanClone(src, target, false, true);
            Check(plan.Deficit == 0 && plan.Items.Count == 3, "plano de clonagem cabe");
            await DiskOperations.Clone(plan, true, false, false, sink);
            var cl = Load(tp);
            Check(cl.Table.Style == TableStyle.Gpt && cl.Table.Entries.Count == 3 && cl.Table.DiskGuid != src.Table.DiskGuid, "clone com tabela própria (GUID novo)");
            Check(SameFiles(Fingerprint(src, true), Fingerprint(cl, true), out var df), "arquivos existentes idênticos no clone (só espaço usado) " + df);
            File.Delete(tp);
            using (var f = new FileStream(tp, FileMode.Create)) f.SetLength(400L << 20);
            await DiskOperations.Clone(DiskOperations.PlanClone(src, Load(tp), false, false), false, false, false, sink);
            Check(SameFiles(baseline, Fingerprint(Load(tp)), out df), "clone completo leva também os apagados " + df);
            File.Delete(tp);
        }

        // 5. imagem + restauração
        {
            var src = Load(orig);
            var img = Path.Combine(Path.GetTempPath(), "lx-backup.img");
            await DiskOperations.CreateImage(src, null, img, false, true, 0, sink);
            Check(File.ReadAllBytes(img).AsSpan().SequenceEqual(File.ReadAllBytes(orig)), "imagem idêntica ao disco");
            Check(File.Exists(img + ".json"), "metadados da imagem gravados");
            var tp = Path.Combine(Path.GetTempPath(), "lx-restore.img");
            using (var f = new FileStream(tp, FileMode.Create)) f.SetLength(300L << 20);
            DiskOperations.RestoreImage(img, Load(tp), null, sink);
            Check(File.ReadAllBytes(tp).AsSpan(0, (int)new FileInfo(orig).Length).SequenceEqual(File.ReadAllBytes(orig)), "restauração idêntica");
            File.Delete(img); File.Delete(img + ".json"); File.Delete(tp);
        }

        // 6. partições perdidas → restaurar tabela
        {
            var p = Copy(Path.Combine(dir, "lost.img"), "lx-lost.img");
            var d = Load(p);
            using (var dev = new ImageFileDevice(p))
            {
                var found = PartitionFinder.Scan(dev, false, null, CancellationToken.None);
                dev.Dispose();
                DiskOperations.RestoreFound(d, found, TableStyle.Gpt, sink);
            }
            var r = Load(p);
            Check(r.Table.Style == TableStyle.Gpt && r.Table.Entries.Count == 3 && r.Partitions.Any(x => x.IsEsp), "tabela restaurada com 3 partições e ESP reconhecida");
            Check(SameFiles(baseline, Fingerprint(r), out var df), "todos os arquivos de volta após restaurar a tabela " + df);
            File.Delete(p);
        }

        // 7. excluir todas + FAT32 grande + limpeza
        {
            var p = Copy(orig, "lx-del.img");
            var d = Load(p);
            DiskOperations.DeleteAllPartitions(d, sink);
            d = Load(p);
            Check(d.Table.Style == TableStyle.Gpt && d.Table.Entries.Count == 0, "todas as partições excluídas");
            using (var dev = new ImageFileDevice(p, true))
            {
                var found = PartitionFinder.Scan(dev, false, null, CancellationToken.None);
                Check(found.Count == 3, "e a busca ainda acha as 3");
                var vol = dev.Slice(1 << 20, 200L << 20);
                Fat32Formatter.Format(vol, "TESTE", 0, 2048);
                var fv = FatVolume.Open(vol, out _);
                Check(FsDetect.Detect(vol) == FsKind.Fat32 && fv != null && fv.ClusterCount > 65525, "FAT32 formatado pelo LibertyX é válido");
                Check(fv != null && UsageCalculator.Compute(vol, FsKind.Fat32)?.label == "TESTE", "rótulo do FAT32 lido de volta");
                Wiper.Wipe(dev, 0, 4 << 20, WipeMethod.DoD3Pass, null, CancellationToken.None);
            }
            File.Delete(p);
        }
        return fails;
    }
}
