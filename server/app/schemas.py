from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class ClusterIn(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str = Field(max_length=32)
    count: int = Field(ge=0)
    exts: dict[str, int] = {}
    pattern: str = Field(default="", max_length=200)
    dates: list[str] = Field(default=[], max_length=4)
    samples: list[str] = Field(default=[], max_length=3)


class FileIn(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str = Field(max_length=32)
    name: str = Field(max_length=512)
    size_kb: int = Field(ge=0)
    date: str = Field(default="", max_length=32)


class PlanRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    phase: int
    existing_folders: list[str] = Field(default=[], max_length=1000)
    allow_existing: bool = False
    taxonomy: Optional[list[str]] = None
    clusters: list[ClusterIn] = Field(default=[], max_length=2000)
    files: list[FileIn] = Field(default=[], max_length=500)

    @field_validator("phase")
    @classmethod
    def check_phase(cls, v: int) -> int:
        if v not in (0, 1, 2):
            raise ValueError("phase must be 0, 1 or 2")
        return v

    @model_validator(mode="after")
    def check_taxonomy(self) -> "PlanRequest":
        if self.phase == 2 and self.taxonomy is None:
            raise ValueError("taxonomy is required in phase 2")
        if self.phase in (0, 1):
            self.taxonomy = None
        ids = [c.id for c in self.clusters] + [f.id for f in self.files]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate ids")
        return self


class FolderOut(BaseModel):
    name: str
    desc: str = ""


class AssignmentOut(BaseModel):
    ref: str
    folder: str
    bundle: Optional[str] = None
    reason: str = ""
    confidence: float


class LeaveOut(BaseModel):
    ref: str
    reason: str = ""


class PlanOut(BaseModel):
    folders: list[FolderOut] = []
    assignments: list[AssignmentOut] = []
    leave: list[LeaveOut] = []
