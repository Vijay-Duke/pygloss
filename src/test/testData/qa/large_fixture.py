def normalize_records(records):
    normalized = []
    for record in records:
        if not record:
            continue
        name = str(record.get("name", "")).strip()
        if not name:
            continue
        normalized.append({
            "name": name,
            "score": int(record.get("score", 0)),
            "tags": [tag.lower() for tag in record.get("tags", []) if tag],
        })
    return normalized


def group_by_tag(records):
    groups = {}
    for record in records:
        for tag in record.get("tags", []):
            bucket = groups.setdefault(tag, [])
            bucket.append(record)
    return groups


def build_report(groups):
    lines = []
    for tag, records in sorted(groups.items()):
        lines.append(f"{tag}: {len(records)}")
        for record in sorted(records, key=lambda item: item["name"]):
            lines.append(f"  - {record['name']} ({record['score']})")
    return "\n".join(lines)


def export_report(records, path):
    normalized = normalize_records(records)
    groups = group_by_tag(normalized)
    report = build_report(groups)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(report)
    return path
