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
    inside: str = Field(default="", max_length=400)


class FileIn(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str = Field(max_length=32)
    name: str = Field(max_length=512)
    size_kb: int = Field(ge=0)
    date: str = Field(default="", max_length=32)
    inside: str = Field(default="", max_length=400)


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


# --- /refine -------------------------------------------------------------------------------------

from typing import Annotated, Literal, Union  # noqa: E402

MAX_OPS = 20
MAX_PINNED = 50


class FolderSummary(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str = Field(max_length=100)
    count: int = Field(ge=0)
    exts: dict[str, int] = {}
    bundles: list[str] = Field(default=[], max_length=100)


class LeaveSummary(BaseModel):
    model_config = ConfigDict(extra="ignore")
    count: int = Field(default=0, ge=0)
    exts: dict[str, int] = {}


class Pinned(BaseModel):
    model_config = ConfigDict(extra="ignore")
    kind: str = Field(max_length=32)
    name: Optional[str] = Field(default=None, max_length=100)
    ref: Optional[str] = Field(default=None, max_length=32)
    folder: Optional[str] = Field(default=None, max_length=100)


class RefineRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    instruction: str = Field(min_length=1, max_length=500)
    existing_folders: list[str] = Field(default=[], max_length=1000)
    allow_existing: bool = False
    folders: list[FolderSummary] = Field(default=[], max_length=200)
    leave: LeaveSummary = LeaveSummary()
    pinned: list[Pinned] = Field(default=[], max_length=MAX_PINNED)
    history: list[str] = Field(default=[], max_length=3)

    @field_validator("instruction")
    @classmethod
    def strip_instruction(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("empty instruction")
        return v


class Selector(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ext: Optional[list[Annotated[str, Field(max_length=16)]]] = Field(default=None, max_length=50)
    name_contains: Optional[str] = Field(default=None, max_length=100)
    name_starts_with: Optional[str] = Field(default=None, max_length=100)
    bundle: Optional[str] = Field(default=None, max_length=100)
    from_folder: Optional[str] = Field(default=None, max_length=100)
    group: Optional[str] = Field(default=None, max_length=32)
    refs: Optional[list[Annotated[str, Field(max_length=32)]]] = Field(default=None, max_length=20)

    @model_validator(mode="after")
    def at_least_one(self) -> "Selector":
        if not any(v not in (None, [], "") for v in self.model_dump().values()):
            raise ValueError("select needs at least one field")
        return self


class _Op(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MoveOp(_Op):
    op: Literal["move"]
    select: Selector
    to: str = Field(min_length=1, max_length=100)


class ToLeaveOp(_Op):
    op: Literal["to_leave"]
    select: Selector


class CreateFolderOp(_Op):
    op: Literal["create_folder"]
    name: str = Field(min_length=1, max_length=100)
    desc: str = Field(default="", max_length=200)


class RenameFolderOp(_Op):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    op: Literal["rename_folder"]
    from_: str = Field(alias="from", min_length=1, max_length=100)
    to: str = Field(min_length=1, max_length=100)


class MergeFoldersOp(_Op):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    op: Literal["merge_folders"]
    from_: list[Annotated[str, Field(max_length=100)]] = Field(alias="from", min_length=1, max_length=20)
    into: str = Field(min_length=1, max_length=100)


class UnbundleOp(_Op):
    op: Literal["unbundle"]
    bundle: str = Field(min_length=1, max_length=100)


PatchOp = Annotated[
    Union[MoveOp, ToLeaveOp, CreateFolderOp, RenameFolderOp, MergeFoldersOp, UnbundleOp],
    Field(discriminator="op"),
]


class RefineOut(BaseModel):
    model_config = ConfigDict(extra="ignore")
    ops: list[PatchOp] = Field(default=[], max_length=MAX_OPS)
    note: str = ""

    @field_validator("note")
    @classmethod
    def clean_note(cls, v: str) -> str:
        v = " ".join(v.replace('"', "").split())
        return v[:120]

    def dump(self) -> dict:
        return {"ops": [o.model_dump(by_alias=True, exclude_none=True) for o in self.ops], "note": self.note}
