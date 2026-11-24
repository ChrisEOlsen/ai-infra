#!/usr/bin/env python3
"""Offline S3 stand-in used only by the backup round-trip harness."""
import pathlib, sys, shutil

args = sys.argv[1:]


def path(value):
    return (
        pathlib.Path("/backup-test") / value.removeprefix("s3://")
        if value.startswith("s3://")
        else pathlib.Path(value)
    )


if args[:2] == ["s3", "cp"]:
    source, target = map(path, args[2:4])
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
elif args[:2] == ["s3api", "head-object"]:
    bucket = args[args.index("--bucket") + 1]
    key = args[args.index("--key") + 1]
    print((pathlib.Path("/backup-test") / bucket / key).stat().st_size)
else:
    raise SystemExit("Unexpected AWS operation in offline backup harness")
