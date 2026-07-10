"""A small, dependency-free file for visually checking PyGloss Reader mode."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Invoice:
    customer: str
    total: float
    flagged: bool = False


def approve_invoices(
    invoices: list[Invoice],
    threshold: float = 100.0,
) -> tuple[list[Invoice], dict[str, float]]:
    """Keep safe invoices above the threshold and total them by customer."""
    approved = [
        invoice
        for invoice in invoices
        if invoice.total >= threshold and not invoice.flagged
    ]
    totals = {invoice.customer: invoice.total for invoice in approved}
    return approved, totals


def print_customer_totals(totals: dict[str, float]) -> None:
    for customer, total in totals.items():
        print(f"{customer}: ${total:.2f}")


if __name__ == "__main__":
    sample_invoices = [
        Invoice("Acme", 150.0),
        Invoice("Northwind", 75.0),
        Invoice("Globex", 220.0, flagged=True),
    ]
    selected, customer_totals = approve_invoices(sample_invoices)
    print(f"Approved {len(selected)} invoices")
    print_customer_totals(customer_totals)
