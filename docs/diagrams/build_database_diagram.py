"""Generate the standalone diagram from the latest exported Room schema."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
schema_dir = root / 'tracker-app/app/schemas/com.utbildning.tracker.data.local.TrackerDatabase'
schema_path = max(schema_dir.glob('[0-9]*.json'), key=lambda path: int(path.stem))
schema = json.loads(schema_path.read_text())['database']
for entity in schema['entities']:
    for index in entity.get('indices', []):
        if index['name'] == 'index_courses_active_color':
            index['where'] = 'isCompleted = 0'
            index['createSql'] += ' WHERE isCompleted = 0'
template = Path(__file__).with_name('database.template.html').read_text()
html = (template
        .replace('__SCHEMA__', json.dumps(schema, ensure_ascii=False))
        .replace('__VERSION__', str(schema['version']))
        .replace('__TABLE_COUNT__', str(len(schema['entities']))))
Path(__file__).with_name('database.html').write_text(html)
