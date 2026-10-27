"""Local AURA API. Audio never enters this process."""
import json
import os
import re
import secrets
import sqlite3
from datetime import datetime, timezone
from contextlib import contextmanager
from typing import Literal
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from contracts_v2 import TypedRuleInput, TypedRule, TypedEventInput, TypedEvent, AudioCondition
from migrations import migrate
from rule_planner import PreviewInput, Preview, preview, openai_client

app = FastAPI(title="AURA", version="0.4.1")
auth = HTTPBearer(auto_error=False)


def authorize(credentials: HTTPAuthorizationCredentials | None = Depends(auth)):
    token = os.getenv("AURA_API_TOKEN", "")
    if len(token) < 24:
        raise HTTPException(503, "Set AURA_API_TOKEN to at least 24 characters")
    if credentials is None or not secrets.compare_digest(credentials.credentials.encode(), token.encode()):
        raise HTTPException(401, "Invalid bearer token")


@contextmanager
def db():
    connection = sqlite3.connect(os.getenv("AURA_DB", "aura.db"), timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript("""
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS rules(id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      text TEXT NOT NULL, threshold REAL NOT NULL, enabled INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      rule_id TEXT REFERENCES rules(id) ON DELETE SET NULL, rms REAL NOT NULL, simulated INTEGER NOT NULL,
      created_at TEXT NOT NULL);
    """)
    migrate(connection)
    try:
        yield connection
        connection.commit()
    finally:
        connection.close()


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DeviceInput(Contract):
    name: str = Field(min_length=1, max_length=80)
    kind: Literal["phone", "sensor"] = "phone"


class Device(DeviceInput):
    id: str


class RuleInput(Contract):
    device_id: str
    text: str = Field(min_length=1, max_length=240)


class Rule(RuleInput):
    id: str
    threshold: float
    enabled: bool


class RuleToggle(Contract):
    enabled: bool


class EventInput(Contract):
    device_id: str
    rule_id: str | None = None
    rms: float = Field(ge=0, le=1, allow_inf_nan=False)


class Event(EventInput):
    id: str
    simulated: bool
    created_at: str


class Status(Contract):
    version: str
    devices: int
    enabled_rules: int
    events: int
    monitoring: str


class ValidationInput(Contract):
    text: str = Field(min_length=1, max_length=240)


class ValidationResult(Contract):
    threshold: float
    capability: str = "uncalibrated_rms"


def parse_rule(text: str) -> float:
    # Deliberately constrained, deterministic natural language. No classification claims.
    match = re.fullmatch(r"notify me when (?:sound level|rms) (?:is )?above (0(?:\.\d+)?|1(?:\.0+)?)", text.strip(), re.I)
    if not match or not 0 < float(match[1]) <= 1:
        raise HTTPException(422, 'Supported: "Notify me when sound level is above 0.15" (threshold > 0 and <= 1). Sound types are unsupported.')
    return float(match[1])


protected = [Depends(authorize)]


@app.get("/api/status", dependencies=protected, response_model=Status)
def status():
    with db() as c:
        return dict(version="0.4.1", devices=c.execute("SELECT count(*) FROM devices").fetchone()[0],
                    enabled_rules=c.execute("SELECT count(*) FROM rules WHERE enabled=1").fetchone()[0],
                    events=c.execute("SELECT count(*) FROM events").fetchone()[0],
                    monitoring="Device-controlled; API does not activate microphones")


@app.get("/api/devices", dependencies=protected, response_model=list[Device])
def devices():
    with db() as c:
        return [dict(r) for r in c.execute("SELECT * FROM devices ORDER BY rowid DESC")]


@app.post("/api/devices", dependencies=protected, response_model=Device, status_code=201)
def create_device(data: DeviceInput):
    result = dict(id=str(uuid4()), **data.model_dump())
    with db() as c:
        c.execute("INSERT INTO devices VALUES (:id,:name,:kind)", result)
    return result


@app.delete("/api/devices/{device_id}", dependencies=protected, status_code=204)
def delete_device(device_id: str):
    with db() as c:
        if c.execute("DELETE FROM devices WHERE id=?", (device_id,)).rowcount == 0:
            raise HTTPException(404, "Device not found")


@app.post("/api/rules/validate", dependencies=protected, response_model=ValidationResult)
def validate_rule(data: ValidationInput):
    return dict(threshold=parse_rule(data.text))


@app.get("/api/rules", dependencies=protected, response_model=list[Rule])
def rules():
    with db() as c:
        return [{k:r[k] for k in ("id","device_id","text","threshold","enabled")} for r in c.execute("SELECT * FROM rules WHERE legacy=1 ORDER BY rowid DESC")]


@app.post("/api/rules", dependencies=protected, response_model=Rule, status_code=201)
def create_rule(data: RuleInput):
    threshold = parse_rule(data.text)
    result = dict(id=str(uuid4()), **data.model_dump(), threshold=threshold, enabled=True)
    with db() as c:
        if not c.execute("SELECT 1 FROM devices WHERE id=?", (data.device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        c.execute("INSERT INTO rules(id,device_id,text,threshold,enabled) VALUES (:id,:device_id,:text,:threshold,:enabled)", result)
    return result


@app.patch("/api/rules/{rule_id}", dependencies=protected, response_model=Rule)
def toggle_rule(rule_id: str, data: RuleToggle):
    with db() as c:
        if not c.execute("UPDATE rules SET enabled=? WHERE id=?", (data.enabled, rule_id)).rowcount:
            raise HTTPException(404, "Rule not found")
        row=c.execute("SELECT * FROM rules WHERE id=?", (rule_id,)).fetchone()
        return {k:row[k] for k in ("id","device_id","text","threshold","enabled")}


def insert_event(data: EventInput, simulated: bool):
    result = dict(id=str(uuid4()), **data.model_dump(), simulated=simulated,
                  created_at=datetime.now(timezone.utc).isoformat())
    with db() as c:
        if not c.execute("SELECT 1 FROM devices WHERE id=?", (data.device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        if data.rule_id:
            rule = c.execute("SELECT * FROM rules WHERE id=? AND device_id=?", (data.rule_id, data.device_id)).fetchone()
            if not rule:
                raise HTTPException(404, "Rule not found for this device")
            if not rule["legacy"]:
                raise HTTPException(422, "Typed rules require the v2 event endpoint")
            if not rule["enabled"] or data.rms <= rule["threshold"]:
                raise HTTPException(422, "Rule disabled or threshold not exceeded")
        c.execute("INSERT INTO events(id,device_id,rule_id,rms,simulated,created_at) VALUES (:id,:device_id,:rule_id,:rms,:simulated,:created_at)", result)
    return result


@app.post("/api/events", dependencies=protected, response_model=Event, status_code=201)
def create_event(data: EventInput):
    return insert_event(data, False)


@app.post("/api/simulate", dependencies=protected, response_model=Event, status_code=201)
def simulate(data: EventInput):
    return insert_event(data, True)


@app.get("/api/events", dependencies=protected, response_model=list[Event])
def events(limit: int = 100):
    if not 1 <= limit <= 500:
        raise HTTPException(422, "limit must be 1..500")
    with db() as c:
        return [{k:r[k] for k in ("id","device_id","rule_id","rms","simulated","created_at")} for r in c.execute("SELECT * FROM events WHERE metadata IS NULL OR json_extract(metadata, '$.event_type')='rms_above' ORDER BY rowid DESC LIMIT ?", (limit,))]


# Versioned endpoints retain v0.2 routes above for old phones and dashboards.
def typed_rule(row):
    if row["spec"]:
        value = json.loads(row["spec"])
    else:
        value = dict(schema_version=2, source=dict(device_id=row["device_id"], modality="audio"),
                     condition=dict(type="rms_above", threshold=row["threshold"], legacy_repeat=True),
                     policy=dict(cooldown_ms=30000, notify=True), text=row["text"])
    return dict(**value, id=row["id"], enabled=bool(row["enabled"]), remote=True)


@app.get("/api/v2/rules", dependencies=protected, response_model=list[TypedRule])
def typed_rules():
    with db() as c:
        return [typed_rule(r) for r in c.execute("SELECT * FROM rules ORDER BY rowid DESC")]


@app.post("/api/v2/rules/preview", dependencies=protected, response_model=Preview)
async def preview_natural_rule(data: PreviewInput, client=Depends(openai_client)):
    # Read-only planning accepts a local phone ID without registering/activating a device.
    return await preview(data,client)


@app.post("/api/v2/rules/validate", dependencies=protected, response_model=TypedRuleInput)
def validate_typed_rule(data: TypedRuleInput):
    return data


def write_typed_rule(data: TypedRuleInput, rule_id: str | None = None):
    with db() as c:
        if not c.execute("SELECT 1 FROM devices WHERE id=?", (data.source.device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        if rule_id:
            old = c.execute("SELECT * FROM rules WHERE id=?", (rule_id,)).fetchone()
            if not old:
                raise HTTPException(404, "Rule not found")
            if old["device_id"] != data.source.device_id:
                raise HTTPException(422, "Cannot move a rule to another device")
        rule_id = rule_id or str(uuid4())
        spec = data.model_dump(mode="json", exclude={"enabled"})
        threshold = data.condition.threshold if data.condition.type == "rms_above" else 0.0
        text = data.text or ("RMS threshold" if data.condition.type == "rms_above" else data.condition.event_type)
        c.execute("""INSERT INTO rules(id,device_id,text,threshold,enabled,spec,legacy) VALUES (?,?,?,?,?,?,0)
            ON CONFLICT(id) DO UPDATE SET text=excluded.text,threshold=excluded.threshold,enabled=excluded.enabled,spec=excluded.spec,legacy=0""",
            (rule_id,data.source.device_id,text,threshold,data.enabled,json.dumps(spec)))
        return typed_rule(c.execute("SELECT * FROM rules WHERE id=?", (rule_id,)).fetchone())


@app.post("/api/v2/rules", dependencies=protected, response_model=TypedRule, status_code=201)
def create_typed_rule(data: TypedRuleInput):
    return write_typed_rule(data)


@app.put("/api/v2/rules/{rule_id}", dependencies=protected, response_model=TypedRule)
def edit_typed_rule(rule_id: str, data: TypedRuleInput):
    return write_typed_rule(data, rule_id)


@app.patch("/api/v2/rules/{rule_id}", dependencies=protected, response_model=TypedRule)
def toggle_typed_rule(rule_id: str, data: RuleToggle):
    with db() as c:
        if not c.execute("UPDATE rules SET enabled=? WHERE id=?", (data.enabled,rule_id)).rowcount:
            raise HTTPException(404, "Rule not found")
        return typed_rule(c.execute("SELECT * FROM rules WHERE id=?", (rule_id,)).fetchone())


@app.delete("/api/v2/rules/{rule_id}", dependencies=protected, status_code=204)
def delete_typed_rule(rule_id: str):
    with db() as c:
        if not c.execute("DELETE FROM rules WHERE id=?", (rule_id,)).rowcount:
            raise HTTPException(404, "Rule not found")


def typed_event(row):
    if row["metadata"]:
        value = json.loads(row["metadata"])
    else:
        value = dict(schema_version=2,device_id=row["device_id"],rule_id=row["rule_id"],rms=row["rms"],
                     event_type="rms_above",provenance="simulated" if row["simulated"] else "device_reported",
                     start_at=row["created_at"],end_at=row["created_at"],duration_ms=0)
    # FK SET NULL must also be reflected in JSON metadata when a rule is deleted.
    value["rule_id"] = row["rule_id"]
    return dict(**value,id=row["id"],created_at=row["created_at"],simulated=bool(row["simulated"]),evidence_verified=False)


def insert_typed_event(data: TypedEventInput, force_simulated=False):
    if force_simulated:
        data = data.model_copy(update={"provenance":"simulated"})
    metadata = data.model_dump(mode="json")
    with db() as c:
        if not c.execute("SELECT 1 FROM devices WHERE id=?", (data.device_id,)).fetchone():
            raise HTTPException(404, "Device not found")
        if data.rule_id:
            row=c.execute("SELECT * FROM rules WHERE id=? AND device_id=?",(data.rule_id,data.device_id)).fetchone()
            if not row:
                raise HTTPException(404, "Rule not found for this device")
            spec=typed_rule(row)
            condition=spec["condition"]
            if not row["enabled"]:
                raise HTTPException(422,"Rule is disabled; historical reports may omit rule_id")
            if condition["type"]=="rms_above":
                if data.event_type!="rms_above" or data.rms<=condition["threshold"]:
                    raise HTTPException(422,"RMS rule condition not satisfied")
            elif data.event_type!=condition["event_type"] or (condition.get("class_id") is not None and data.class_id!=condition["class_id"]) or (data.peak_score or 0)<condition["min_score"]:
                raise HTTPException(422,"Reported metadata does not match rule")
        if data.client_event_id:
            old=c.execute("SELECT * FROM events WHERE device_id=? AND client_event_id=?",(data.device_id,data.client_event_id)).fetchone()
            if old:
                if old["metadata"] != json.dumps(metadata,sort_keys=True):
                    raise HTTPException(409,"client_event_id already exists with different metadata")
                return typed_event(old)
        event_id=str(uuid4()); now=datetime.now(timezone.utc).isoformat()
        c.execute("INSERT INTO events(id,device_id,rule_id,rms,simulated,created_at,metadata,client_event_id) VALUES (?,?,?,?,?,?,?,?)",
                  (event_id,data.device_id,data.rule_id,data.rms,data.provenance=="simulated",now,json.dumps(metadata,sort_keys=True),data.client_event_id))
        return typed_event(c.execute("SELECT * FROM events WHERE id=?",(event_id,)).fetchone())


@app.post("/api/v2/events", dependencies=protected, response_model=TypedEvent, status_code=201)
def create_typed_event(data: TypedEventInput):
    return insert_typed_event(data)


@app.post("/api/v2/simulate", dependencies=protected, response_model=TypedEvent, status_code=201)
def simulate_typed_event(data: TypedEventInput):
    return insert_typed_event(data,True)


@app.get("/api/v2/events", dependencies=protected, response_model=list[TypedEvent])
def typed_events(limit: int=100):
    if not 1<=limit<=500:
        raise HTTPException(422,"limit must be 1..500")
    with db() as c:
        return [typed_event(row) for row in c.execute("SELECT * FROM events ORDER BY rowid DESC LIMIT ?",(limit,))]
