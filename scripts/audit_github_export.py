"""Read-only Git/source audit and local backup, without add/commit/push.

Reports and backups stay in ignored build/github-preflight. Never prints matched
secret values, reads the app's credentials or changes refs/index/remotes.
"""
import hashlib,json,re,subprocess,zipfile
from collections import Counter
from datetime import datetime,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/github-preflight'
PATTERNS={
    'google_api_key':rb'(?:AQ\.[A-Za-z0-9_-]{35,}|AIza[0-9A-Za-z_-]{30,})',
    'github_token':rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})',
    'private_key_header':rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
}
DISALLOWED={'.exe','.apk','.zip','.7z','.db','.sqlite','.sqlite3','.pem','.key','.p12','.pfx','.jks','.keystore','.mp4','.mkv','.mov','.wav','.onnx','.bin'}

def git(*args):
    return subprocess.check_output(['git',*args],cwd=ROOT).decode('utf-8').strip()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file()else None

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    gitroot=Path(git('rev-parse','--show-toplevel')).resolve()
    gitdir=Path(git('rev-parse','--absolute-git-dir')).resolve()
    assert gitroot==ROOT and gitdir==ROOT/'.git','Unexpected repository: stop rather than export unrelated code.'
    metadata_before={name:digest(gitdir/name)for name in ('HEAD','config','index')}
    names=[name for name in git('ls-files','--cached','--others','--exclude-standard','-z').split('\0')if name]
    manifest=[];flags=[]
    for name in sorted(set(names)):
        path=(ROOT/name).resolve()
        if not path.is_file():continue
        assert path.is_relative_to(ROOT)and not path.is_relative_to(gitdir),'Unsafe export path'
        data=path.read_bytes()
        for kind,pattern in PATTERNS.items():
            if re.search(pattern,data):flags.append(dict(file=name,kind=kind))
        if path.suffix.lower()in DISALLOWED:flags.append(dict(file=name,kind='unexpected_data_or_build_file'))
        manifest.append(dict(path=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    refs=[]
    for line in git('for-each-ref','--format=%(refname) %(objectname)').splitlines():
        name,sha=line.split();refs.append(dict(ref=name,sha=sha,type=git('cat-file','-t',sha)))
    report=dict(created_at=datetime.now(timezone.utc).isoformat(),project_root=str(ROOT),git_directory=str(gitdir),
        current_branch=git('branch','--show-current'),commit_count=int(git('rev-list','--all','--count')),
        tracked_index_entries=len(git('ls-files','--stage').splitlines()),remotes=git('remote','-v').splitlines(),
        refs=refs,worktrees=git('worktree','list','--porcelain').splitlines(),
        source_files=len(manifest),source_bytes=sum(row['bytes']for row in manifest),
        groups=dict(Counter(row['path'].split('/')[0]for row in manifest)),secret_or_data_flags=flags,
        stage_commit_push_executed=False,application_credentials_read=False,
        excluded=['.tools/','.python-deps/','.gradle-home/','.worktrees/','.gemini/','.env*',
            'credentials/signing/TLS files','dist/','build/','SQLite/cache/log/runtime',
            'generated raw reports and local transcripts','Windows app data outside project'],
        permissions_required=['Create new PRIVATE GitHub repository gemini-live-ocr-translator',
            'Create initial source commit; preserve existing internal Codex refs locally',
            'Add origin for that new repository only; push main/codex-dev/gemini-review',
            'Create two worktrees; do not alter existing app or global Gemini config'])
    (OUT/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    if not flags:
        # Both archives are strictly local, ignored, and never uploaded.
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        source_backup=f'source-before-migration-{stamp}.zip'
        git_backup=f'git-metadata-before-migration-{stamp}.zip'
        with zipfile.ZipFile(OUT/source_backup,'x',zipfile.ZIP_DEFLATED)as archive:
            for row in manifest:archive.write(ROOT/row['path'],arcname=row['path'])
        with zipfile.ZipFile(OUT/git_backup,'x',zipfile.ZIP_DEFLATED)as archive:
            for path in gitdir.rglob('*'):
                if path.is_file()and path.resolve().is_relative_to(gitdir):archive.write(path,arcname='.git/'+path.relative_to(gitdir).as_posix())
        report['local_backups']={name:digest(OUT/name)for name in (source_backup,git_backup)}
    report['git_metadata_unchanged']=metadata_before=={name:digest(gitdir/name)for name in metadata_before}
    assert report['git_metadata_unchanged']
    (OUT/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({key:report[key]for key in ('project_root','commit_count','tracked_index_entries','source_files','source_bytes','groups','secret_or_data_flags','git_metadata_unchanged','stage_commit_push_executed')},ensure_ascii=False))

if __name__=='__main__':main()
