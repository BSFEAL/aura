"""Additive v0.2 → v0.3 migration. No table rebuild, IDs, rows or history discarded."""
import json


def migrate(connection):
    # Serialize concurrent first-open migrations and re-read columns while holding the write lock.
    connection.execute('BEGIN IMMEDIATE')
    try:
        for table, additions in {
            'rules': {'spec': 'TEXT', 'legacy': 'INTEGER NOT NULL DEFAULT 1'},
            'events': {'metadata': 'TEXT', 'client_event_id': 'TEXT'},
        }.items():
            columns = {row[1] for row in connection.execute(f'PRAGMA table_info({table})')}
            for name, declaration in additions.items():
                if name not in columns:
                    connection.execute(f'ALTER TABLE {table} ADD COLUMN {name} {declaration}')
        connection.execute('CREATE UNIQUE INDEX IF NOT EXISTS events_client_identity ON events(device_id,client_event_id) WHERE client_event_id IS NOT NULL')
        for row in connection.execute('SELECT id,device_id,text,threshold FROM rules WHERE spec IS NULL'):
            spec = dict(schema_version=2, source=dict(device_id=row['device_id'],modality='audio'),
                        condition=dict(type='rms_above',threshold=row['threshold'],legacy_repeat=True),
                        policy=dict(cooldown_ms=30000,notify=True),text=row['text'])
            connection.execute('UPDATE rules SET spec=? WHERE id=?',(json.dumps(spec),row['id']))
        connection.execute('PRAGMA user_version=2')
        connection.commit()
    except Exception:
        connection.rollback()
        raise
