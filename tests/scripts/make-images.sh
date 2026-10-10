#!/bin/bash
# Gera discos de teste com arquivos apagados de propósito. Roda dentro de um contêiner
# Debian privilegiado (precisa de loop e fuse). Uso: docker run ... /scripts/make-images.sh
set -euo pipefail
apt-get update -qq >/dev/null
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq dosfstools ntfs-3g exfatprogs gdisk util-linux python3 imagemagick attr >/dev/null 2>&1
cd /work
rm -f disk.img manifest.txt
truncate -s 256M disk.img
sgdisk -o -n1:2048:+64M -t1:ef00 -c1:"EFI system partition" -n2:0:+120M -t2:0700 -c2:"Dados" -n3:0:0 -t3:0700 -c3:"Pendrive" disk.img >/dev/null
read S1 E1 < <(sgdisk -i1 disk.img | awk '/First sector/{f=$3}/Last sector/{l=$3}END{print f, l}')
read S2 E2 < <(sgdisk -i2 disk.img | awk '/First sector/{f=$3}/Last sector/{l=$3}END{print f, l}')
read S3 E3 < <(sgdisk -i3 disk.img | awk '/First sector/{f=$3}/Last sector/{l=$3}END{print f, l}')
L1=$(losetup -f --show -o $((S1*512)) --sizelimit $(((E1-S1+1)*512)) disk.img)
L2=$(losetup -f --show -o $((S2*512)) --sizelimit $(((E2-S2+1)*512)) disk.img)
L3=$(losetup -f --show -o $((S3*512)) --sizelimit $(((E3-S3+1)*512)) disk.img)
trap 'umount /mnt/a /mnt/b /mnt/c 2>/dev/null; losetup -d $L1 $L2 $L3 2>/dev/null' EXIT
mkfs.vfat -F 32 -n EFI $L1 >/dev/null
mkfs.ntfs -Q -L DADOS $L2 >/dev/null 2>&1
mkfs.exfat -n PENDRIVE $L3 >/dev/null
mkdir -p /mnt/a /mnt/b /mnt/c /src
mount -t vfat $L1 /mnt/a
ntfs-3g $L2 /mnt/b
mount -t exfat $L3 /mnt/c

# arquivos de origem
cd /src
convert -size 800x600 plasma:fractal -quality 90 ferias1.jpg
convert -size 640x480 plasma:red-blue -quality 85 ferias2.jpg
convert -size 300x200 gradient:gold-black logo.png
convert -size 200x200 plasma: -quality 80 foto_fat.jpg
python3 - <<'PY'
import zipfile, sqlite3, os
def pdf(path, text):
    objs=[b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
          b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R >>"]
    stream=("BT /F1 18 Tf 20 100 Td (%s) Tj ET" % text).encode()
    objs.append(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
    out=b"%PDF-1.4\n"; offs=[]
    for i,o in enumerate(objs):
        offs.append(len(out)); out+=b"%d 0 obj\n" % (i+1) + o + b"\nendobj\n"
    x=len(out); out+=b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs)+1)
    for o in offs: out+=b"%010d 00000 n \n" % o
    out+=b"trailer << /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs)+1, x)
    out+=os.urandom(0)  # deterministic
    open(path,"wb").write(out + b"% " + b"x"*3000 + b"\n%%EOF\n")
pdf("contrato.pdf","Contrato LibertyX")
pdf("Documento com nome longo de verdade.pdf","FAT LFN")
with zipfile.ZipFile("relatorio.docx","w") as z:
    z.writestr("[Content_Types].xml","<Types/>"); z.writestr("word/document.xml","<w:document>"+"relatorio "*2000+"</w:document>")
with zipfile.ZipFile("backup.zip","w") as z:
    z.writestr("a.txt","conteudo "*5000)
c=sqlite3.connect("mensagens.sqlite"); c.execute("create table m(t text)"); c.executemany("insert into m values(?)",[("msg %d"%i,) for i in range(3000)]); c.commit(); c.close()
open("notas.txt","w").write("lista de compras: pao, leite\n")
open("big.bin","wb").write(os.urandom(2*1024*1024))
open("texto_comprimido.txt","w").write(("linha repetida para comprimir bem %d\n" % 7)*20000)
open("BOOTX64.EFI","wb").write(os.urandom(100*1024))
PY

# NTFS
mkdir -p /mnt/b/Fotos /mnt/b/Documentos/Antigos /mnt/b/Comprimidos
cp ferias1.jpg ferias2.jpg /mnt/b/Fotos/
cp contrato.pdf relatorio.docx notas.txt /mnt/b/Documentos/
cp big.bin mensagens.sqlite /mnt/b/Documentos/Antigos/
setfattr -h -v 0x00000800 -n system.ntfs_attrib_be /mnt/b/Comprimidos 2>/dev/null || true
cp texto_comprimido.txt /mnt/b/Comprimidos/
sync
# FAT32
mkdir -p /mnt/a/EFI/BOOT /mnt/a/Fotos
cp BOOTX64.EFI /mnt/a/EFI/BOOT/
cp foto_fat.jpg "Documento com nome longo de verdade.pdf" /mnt/a/Fotos/
# exFAT
mkdir -p /mnt/c/Trabalho
cp logo.png backup.zip /mnt/c/Trabalho/
cp ferias1.jpg /mnt/c/
sync

sha256sum ferias1.jpg ferias2.jpg contrato.pdf relatorio.docx notas.txt big.bin mensagens.sqlite texto_comprimido.txt foto_fat.jpg "Documento com nome longo de verdade.pdf" logo.png backup.zip > /work/manifest.txt

# apaga
rm /mnt/b/Fotos/ferias2.jpg /mnt/b/Documentos/contrato.pdf /mnt/b/Documentos/notas.txt
rm -rf /mnt/b/Documentos/Antigos /mnt/b/Comprimidos
rm "/mnt/a/Fotos/Documento com nome longo de verdade.pdf" /mnt/a/Fotos/foto_fat.jpg
rm /mnt/c/Trabalho/logo.png /mnt/c/Trabalho/backup.zip
sync
umount /mnt/a /mnt/b /mnt/c
echo "S1=$S1 S2=$S2 S3=$S3" >> /work/manifest.txt

# variante: tabela de partições destruída (para a recuperação de partição)
cp /work/disk.img /work/lost.img
dd if=/dev/zero of=/work/lost.img bs=512 count=34 conv=notrunc status=none
SZ=$(stat -c %s /work/lost.img)
dd if=/dev/zero of=/work/lost.img bs=512 seek=$((SZ/512-33)) count=33 conv=notrunc status=none
chmod 666 /work/*.img /work/manifest.txt
echo PRONTO
