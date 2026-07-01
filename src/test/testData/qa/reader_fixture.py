import pathlib


class ReportWriter:
    def __init__(self, output_dir):
        self.output_dir = pathlib.Path(output_dir)

    async def load_items(self, client, *ids, **options):
        rows = []
        for item_id in ids:
            row = await client.fetch(item_id, include_meta=options.get("meta", False))
            if row and (name := row.get("name")):
                rows.append({"id": item_id, "name": name})
        return rows

    def write_selected(self, rows, filename):
        target = self.output_dir / filename
        with target.open("w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(f"{row['id']}: {row['name']}\n")
        return target


def summarize_status(rows):
    active = [row for row in rows if row.get("active")]
    if not active:
        return "empty"
    if len(active) > 10:
        return "large"
    return "small"
