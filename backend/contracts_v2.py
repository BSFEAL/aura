"""Version 2 contracts. Client model scores are unverified metadata, not probabilities."""
from datetime import timedelta
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, AwareDatetime, model_validator

MODEL = 'yamnet-tflite-1-10c95ea3'
MAPPING = 'aura-audioset-v1'
THRESHOLDS = 'provisional-v1'
EVENT_CLASSES = {
    'dog_bark': {'/m/05tny_'},
    'alarm_siren': {'/m/07pp_mv', '/m/03kmc9'},
    'rain': {'/m/06mb1', '/m/07r10fb', '/t/dd00038'},
    'running_water': {'/m/02jz0l'},
    'knocking': {'/m/07r4wb8'},
    'doorbell': {'/m/03wwcy'},
}
EventType = Literal['dog_bark', 'alarm_siren', 'rain', 'running_water', 'knocking', 'doorbell']
Score = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False, strict=True)]

class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid')

class Source(Strict):
    device_id: str = Field(min_length=1, max_length=80)
    modality: Literal['audio'] = 'audio'

class RmsCondition(Strict):
    type: Literal['rms_above'] = 'rms_above'
    threshold: float = Field(gt=0, le=1, allow_inf_nan=False, strict=True)
    legacy_repeat: bool = Field(default=False, strict=True)

class AudioCondition(Strict):
    type: Literal['audio_event'] = 'audio_event'
    event_type: EventType
    min_score: Score = .7
    release_score: Score = .5
    min_duration_ms: int = Field(default=3000, ge=0, le=60000, strict=True)
    max_gap_ms: int = Field(default=1000, ge=0, le=10000, strict=True)
    ema_alpha: float = Field(default=.5, ge=.01, le=1, allow_inf_nan=False, strict=True)
    model_version: Literal['yamnet-tflite-1-10c95ea3'] = MODEL
    mapping_version: Literal['aura-audioset-v1'] = MAPPING
    threshold_version: Literal['provisional-v1'] = THRESHOLDS

    @model_validator(mode='after')
    def hysteresis(self):
        if self.min_score <= self.release_score:
            raise ValueError('release_score must be below min_score')
        return self

Condition = Annotated[RmsCondition | AudioCondition, Field(discriminator='type')]

class Policy(Strict):
    cooldown_ms: int = Field(default=30000, ge=0, le=3600000, strict=True)
    notify: bool = Field(default=True, strict=True)

class TypedRuleInput(Strict):
    schema_version: Literal[2] = 2
    enabled: bool = Field(default=True, strict=True)
    source: Source
    condition: Condition
    policy: Policy = Field(default_factory=Policy)
    text: str = Field(default='', max_length=240)

class TypedRule(TypedRuleInput):
    id: str
    remote: bool = True

class TypedEventInput(Strict):
    schema_version: Literal[2] = 2
    device_id: str = Field(min_length=1, max_length=80)
    client_event_id: str | None = Field(default=None, min_length=1, max_length=80)
    rule_id: str | None = Field(default=None, min_length=1, max_length=80)
    event_type: Literal['rms_above', 'dog_bark', 'alarm_siren', 'rain', 'running_water', 'knocking', 'doorbell'] = 'rms_above'
    class_id: str | None = Field(default=None, max_length=80)
    label: str = Field(default='', max_length=120)
    rms: Score = 0.0
    peak_score: Score | None = None
    mean_score: Score | None = None
    model_version: str | None = Field(default=None, min_length=1, max_length=80)
    mapping_version: str | None = Field(default=None, min_length=1, max_length=80)
    threshold_version: str | None = Field(default=None, min_length=1, max_length=80)
    provenance: Literal['model_detected', 'simulated', 'device_reported'] = 'device_reported'
    start_at: AwareDatetime
    end_at: AwareDatetime | None = None
    duration_ms: int = Field(default=0, ge=0, le=86400000, strict=True)
    notification_status: Literal['posted', 'disabled', 'permission_denied', 'legacy_unknown', 'error'] = 'disabled'
    state: Literal['active', 'ended', 'interrupted', 'clock_or_capture_gap', 'rule_changed_or_unavailable', 'process_terminated'] = 'ended'

    @model_validator(mode='after')
    def metadata(self):
        if self.start_at.year < 2000 or self.start_at.year > 2100:
            raise ValueError('start_at outside supported epoch')
        if self.end_at is not None:
            elapsed = (self.end_at - self.start_at).total_seconds()*1000
            if elapsed < 0 or abs(elapsed-self.duration_ms) > 1:
                raise ValueError('end_at and duration_ms must agree with start_at')
        elif self.duration_ms != 0 or self.state != 'active':
            raise ValueError('Ended episodes require end_at; active episodes have duration_ms=0')
        if self.event_type != 'rms_above':
            if self.class_id not in EVENT_CLASSES[self.event_type]:
                raise ValueError('class_id does not belong to event_type')
            if self.peak_score is None or self.mean_score is None or self.mean_score > self.peak_score:
                raise ValueError('Finite mean_score <= peak_score required')
            if (self.model_version, self.mapping_version, self.threshold_version) != (MODEL, MAPPING, THRESHOLDS):
                raise ValueError('Unsupported model, mapping or threshold version')
            if self.provenance == 'device_reported':
                raise ValueError('Sound classification requires model_detected or simulated provenance')
        elif self.provenance == 'model_detected' or any(x is not None for x in (self.class_id,self.peak_score,self.mean_score,self.model_version,self.mapping_version,self.threshold_version)):
            raise ValueError('RMS events cannot claim classifier evidence')
        return self

class TypedEvent(TypedEventInput):
    id: str
    created_at: AwareDatetime
    simulated: bool
    evidence_verified: Literal[False] = False
