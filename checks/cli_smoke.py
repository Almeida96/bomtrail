"""Exercise installed launchers, source evidence, immutable snapshots and exit codes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--install-dir', required=True, type=Path)
installation = parser.parse_args().install_dir.resolve()
launcher = installation / 'bin' / ('bomtrail.bat' if os.name == 'nt' else 'bomtrail')


def run(*args, expected=0):
    result = subprocess.run([str(launcher), *map(str, args)], capture_output=True,
                            text=True, encoding='utf-8', timeout=40)
    assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout


def pointer(document, path):
    for key in path.lstrip('/').split('/'):
        key = key.replace('~1', '/').replace('~0', '~')
        document = document[int(key)] if isinstance(document, list) else document[key]
    return document


with tempfile.TemporaryDirectory(prefix='bomtrail-cli-') as directory:
    root = Path(directory)
    demo = root / 'demo'
    assert run('--version').strip() == 'bomtrail 0.1.0'
    assert json.loads(run('demo', '--dir', demo))['comparison'] == 'CHANGED'
    db = demo / 'boms.db'
    original = hashlib.sha256(db.read_bytes()).hexdigest()
    baseline = json.loads((demo / 'baseline.json').read_text(encoding='utf-8'))
    summary = json.loads(run('summary', '--db', db, '--snapshot', 'baseline'))
    assert summary['nodes'] == 5 and summary['graphDeclarationsComplete']
    page = json.loads(run('list', '--db', db, '--limit', 1))
    assert page['nextOffset'] == 1
    found = json.loads(run('components', '--db', db, '--snapshot', 'baseline', '--query', 'json-codec'))
    assert found['total'] == 1 and found['components'][0]['ref'] == 'codec'
    path = json.loads(run('path', '--db', db, '--snapshot', 'baseline', '--to', 'codec'))
    assert path['status'] == 'FOUND' and path['distance'] == 2
    for edge in path['edges']:
        assert pointer(baseline, edge['sourcePointer']) == edge['to']
    assert json.loads(run('path', '--db', db, '--snapshot', 'baseline', '--to', 'codec', '--max-depth', 1))['status'] == 'DEPTH_LIMIT'
    impact = json.loads(run('impact', '--db', db, '--snapshot', 'baseline', '--ref', 'codec'))
    assert impact['total'] == 3 and impact['directDependents'] == 2
    comparison = json.loads(run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'candidate', expected=2))
    assert comparison['componentChangeCount'] == 5 and comparison['edgeChangeCount'] == 2
    assert json.loads(run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'baseline'))['status'] == 'UNCHANGED'
    run('inspect', '--db', db, '--snapshot', 'baseline', '--ref', 'codec', '--format', 'md', '--out', root / 'component.md')
    run('summary', '--db', db, '--snapshot', 'baseline', '--out', demo / 'comparison.html', expected=1)
    run('components', '--db', db, '--snapshot', 'baseline', '--limit', '1.5', expected=1)
    run('impact', '--db', db, '--snapshot', 'baseline', '--ref', 'missing', expected=1)
    run('summary', '--db', root / 'absent.db', '--snapshot', 'baseline', expected=1)
    assert not (root / 'absent.db').exists()
    assert hashlib.sha256(db.read_bytes()).hexdigest() == original

    # Same import is idempotent; a new project under an existing name is not.
    run('import', '--db', db, '--file', demo / 'baseline.json', '--name', 'baseline', '--project', 'shop-app')
    run('import', '--db', db, '--file', demo / 'baseline.json', '--name', 'baseline', '--project', 'another-project', expected=1)
    assert hashlib.sha256(db.read_bytes()).hexdigest() == original
    incomplete = root / 'incomplete.json'
    baseline['dependencies'].pop()
    incomplete.write_text(json.dumps(baseline), encoding='utf-8')
    run('import', '--db', db, '--file', incomplete, '--name', 'incomplete', '--project', 'shop-app')
    uncertain = json.loads(run('compare', '--db', db, '--baseline', 'baseline', '--candidate', 'incomplete', expected=3))
    assert uncertain['status'] == 'INCONCLUSIVE'
    html_before = (demo / 'comparison.html').read_bytes()
    run('demo', '--dir', demo, expected=1)
    assert html_before == (demo / 'comparison.html').read_bytes()

print('Installed CLI passed: source pointers, paths, reverse impact, all comparison exits, immutable snapshots and read-only queries.')
