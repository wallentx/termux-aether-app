#!/usr/bin/env python3
import argparse,hashlib,io,os,shutil,subprocess,tarfile,tempfile
from pathlib import Path
parser=argparse.ArgumentParser(description='Validate a suite with real Pacman in an isolated root; never installs into the live prefix.')
parser.add_argument('package', type=Path)
suite=parser.parse_args().package.resolve(strict=True)
real=Path(os.environ['PREFIX'])/'lib/libtermux-exec-ld-preload.so'
before=hashlib.sha256(real.read_bytes()).hexdigest()
with tempfile.TemporaryDirectory(prefix='aether-isolated-pacman-') as temporary:
 root=Path(temporary); install=root/'root'; install.mkdir(); db=root/'db'; db.mkdir(); hooks=root/'hooks'; hooks.mkdir(); cache=root/'cache';cache.mkdir()
 conf=root/'pacman.conf';conf.write_text('[options]\nArchitecture = aarch64\nSigLevel = Never\n')
 def fixture(name, extra, files):
  archive=root/(name+'.pkg.tar.xz')
  with tarfile.open(archive,'w:xz') as tf:
   metadata=f'pkgname = {name}\npkgver = 1:1.0-1\npkgdesc = isolated fixture\narch = aarch64\nsize = 0\n'+extra
   for path,data in {'.PKGINFO':metadata.encode(),**files}.items():
    m=tarfile.TarInfo(path);m.size=len(data);m.mode=0o700;tf.addfile(m,io.BytesIO(data))
  return archive
 prefix='data/data/com.termux/files/usr/'
 fixtures=[fixture('fixture-deps',''.join(f'provides = {p}=9999\n' for p in ['bash','util-linux','termux-am','python','openssh']),{}),
           fixture('termux-api','', {prefix+'bin/termux-battery-status':b'old api'}),
           fixture('termux-aether-exec','provides = termux-exec=1:2.5.0\n',{prefix+'lib/libtermux-exec-ld-preload.so':real.read_bytes()})]
 common=['pacman','--root',str(install),'--dbpath',str(db),'--logfile',str(root/'pacman.log'),'--config',str(conf),'--cachedir',str(cache),'--hookdir',str(hooks),'--noscriptlet','--noprogressbar']
 subprocess.run(common+['-U',*[str(p) for p in fixtures]],input='y\n',text=True,check=True,stdout=subprocess.DEVNULL)
 # Model the risky library being removed as a conflicting package is replaced.
 env=dict(os.environ,LD_PRELOAD=str(install/prefix/'lib/libtermux-exec-ld-preload.so'))
 log=root/'exec.trace'
 result=subprocess.run(['strace','-f','-s','512','-e','trace=execve','-o',str(log),*common,'-U',str(suite)],env=env,input='y\ny\ny\n',text=True,capture_output=True)
 print(result.stdout[-3000:]);print(result.stderr)
 assert result.returncode==0,result.returncode
 assert '/xz' not in log.read_text() and '/zstd' not in log.read_text(),log.read_text()
 q=subprocess.check_output([p for p in common if p not in ('--noscriptlet', '--noprogressbar')]+['-Q','termux-aether-suite'],text=True).strip();print(q)
 for name in ['termux-api','termux-aether-exec']:
  # -Q accepts virtual providers, so query the actual database directories.
  assert not list((db/'local').glob(name+'-*'))
 subprocess.run([p for p in common if p not in ('--noscriptlet', '--noprogressbar')]+['-Qk','termux-aether-suite'],check=True)
 assert hashlib.sha256(real.read_bytes()).hexdigest()==before
 print('PASS: isolated real Pacman replacement, no wildcard overwrite, no external decompressor; live preload unchanged.')
