"""Acceptance path against a running stack (docker compose up, or the jars by hand).

usage: python3 -I scripts/smoke.py [bridge_url] [erp_url]
Checks, in order: CapabilityStatement, the seed reaching the ERP as exactly one invoice per encounter,
an invalid resource rejected with an OperationOutcome, the planted divergence found by reconciliation,
an ERP outage that loses nothing, a permanent failure reprocessed from the DLQ, Prometheus metrics.
Standard library only.
"""
import json
import sys
import time
import urllib.error
import urllib.request

BRIDGE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
ERP = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8090"
SEED_ENCOUNTERS = 50
PLANTED_DATE = "2026-10-03"
ERP_CUSTOMER = "https://health-data-bridge.example/erp/customer"


def call(method, url, body=None, content_type="application/json"):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": content_type})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw and raw.lstrip()[:1] in "{[" else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw and raw.lstrip()[:1] in "{[" else raw)


def wait_for(what, check, timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if check():
            print(f"ok   {what}")
            return
        time.sleep(1)
    sys.exit(f"FAIL {what} (after {timeout}s)")


def expect(what, condition):
    if not condition:
        sys.exit(f"FAIL {what}")
    print(f"ok   {what}")


def outbox_metric(name):
    status, body = call("GET", f"{BRIDGE}/actuator/metrics/{name}")
    return body["measurements"][0]["value"] if status == 200 else None


def encounter_bundle(encounter_id, patient_id, coverage_id, day):
    cond = f"urn:uuid:{encounter_id}-condition"
    return {
        "resourceType": "Bundle", "type": "transaction",
        "entry": [
            {"fullUrl": cond, "request": {"method": "POST", "url": "Condition"}, "resource": {
                "resourceType": "Condition",
                "code": {"coding": [{"system": "http://hl7.org/fhir/sid/icd-10", "code": "J06.9"}]},
                "subject": {"reference": f"Patient/{patient_id}"}}},
            {"request": {"method": "POST", "url": "Claim"}, "resource": {
                "resourceType": "Claim", "status": "active", "use": "claim",
                "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/claim-type", "code": "institutional"}]},
                "patient": {"reference": f"Patient/{patient_id}"}, "created": f"{day}T11:00:00Z",
                "provider": {"display": "Hospital Exemplo (fictício)"},
                "priority": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/processpriority", "code": "normal"}]},
                "insurance": [{"sequence": 1, "focal": True, "coverage": {"reference": f"Coverage/{coverage_id}"}}],
                "item": [{"sequence": 1,
                          "productOrService": {"coding": [{"code": "CONS-01", "display": "Consulta em consultório"}]},
                          "quantity": {"value": 1}, "unitPrice": {"value": 150.00, "currency": "BRL"},
                          "encounter": [{"reference": f"Encounter/{encounter_id}"}]}],
                "total": {"value": 150.00, "currency": "BRL"}}},
            {"request": {"method": "PUT", "url": f"Encounter/{encounter_id}"}, "resource": {
                "resourceType": "Encounter", "id": encounter_id, "status": "finished",
                "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
                "subject": {"reference": f"Patient/{patient_id}"},
                "period": {"start": f"{day}T10:00:00Z", "end": f"{day}T10:30:00Z"},
                "diagnosis": [{"condition": {"reference": cond}}]}},
        ]}


def invoices_for(encounter_id):
    return call("GET", f"{ERP}/api/invoices?encounterId={encounter_id}")[1]


def main():
    status, cs = call("GET", f"{BRIDGE}/fhir/metadata")
    types = {r["type"] for r in cs["rest"][0]["resource"]}
    expect("GET /fhir/metadata is a CapabilityStatement with the 8 resources",
           status == 200 and cs["resourceType"] == "CapabilityStatement"
           and {"Patient", "Encounter", "Coverage", "Claim", "Condition", "Organization", "Location", "Practitioner"} <= types)

    wait_for(f"seed: {SEED_ENCOUNTERS} encounters invoiced in the ERP, one each",
             lambda: (lambda s: s["invoices"] == SEED_ENCOUNTERS + 1 and not s["duplicatedEncounters"])(
                 call("GET", f"{ERP}/admin/stats")[1]))

    status, oo = call("POST", f"{BRIDGE}/fhir/Patient", {"resourceType": "Patient", "communication": [{"preferred": True}]},
                      "application/fhir+json")
    expect("invalid Patient is rejected with an OperationOutcome",
           status == 422 and oo["resourceType"] == "OperationOutcome" and oo["issue"])

    status, report = call("POST", f"{BRIDGE}/admin/reconciliation/run?date={PLANTED_DATE}")
    issues = report["issues"]
    expect("reconciliation finds the planted divergence, and only it",
           status == 200 and len(issues) == 1 and issues[0]["kind"] == "ORPHAN_INVOICE"
           and issues[0]["resourceId"] == "Encounter/enc-planted")

    call("PUT", f"{ERP}/admin/faults/invoices", {"down": True})
    call("POST", f"{BRIDGE}/fhir", encounter_bundle("enc-smoke-outage", "pat-01", "cov-01", "2026-10-06"),
         "application/fhir+json")
    time.sleep(3)
    waiting = [o for o in call("GET", f"{BRIDGE}/admin/outbox?status=PENDING")[1] if o["aggregateId"] == "enc-smoke-outage"]
    expect("ERP down: the event waits in the outbox", outbox_metric("bridge.outbox.pending") >= 1 and len(waiting) == 1)
    call("DELETE", f"{ERP}/admin/faults")
    wait_for("ERP back: the event arrives exactly once", lambda: len(invoices_for("enc-smoke-outage")) == 1, 90)

    call("PUT", f"{BRIDGE}/fhir/Coverage/cov-smoke", {
        "resourceType": "Coverage", "id": "cov-smoke", "status": "active",
        "beneficiary": {"reference": "Patient/pat-02"},
        "payor": [{"identifier": {"system": ERP_CUSTOMER, "value": "CUST-099"}}]}, "application/fhir+json")
    call("POST", f"{BRIDGE}/fhir", encounter_bundle("enc-smoke-dlq", "pat-02", "cov-smoke", "2026-10-06"),
         "application/fhir+json")
    dead = []

    def in_dlq():
        dead[:] = [o for o in call("GET", f"{BRIDGE}/admin/outbox?status=DEAD")[1] if o["aggregateId"] == "enc-smoke-dlq"]
        return bool(dead)

    wait_for("inactive insurer: the event lands in the DLQ", in_dlq, 60)
    call("PUT", f"{ERP}/admin/customers/CUST-099", {"name": "Operadora Reativada (fictícia)", "active": True})
    status, revived = call("POST", f"{BRIDGE}/admin/outbox/{dead[0]['id']}/retry")
    expect("POST /admin/outbox/{id}/retry puts it back in line", status == 200 and revived["status"] == "PENDING")
    wait_for("reprocessed event is invoiced", lambda: len(invoices_for("enc-smoke-dlq")) == 1, 60)

    status, prom = call("GET", f"{BRIDGE}/actuator/prometheus")
    expect("Prometheus exposes outbox and breaker metrics",
           status == 200 and all(m in prom for m in ("bridge_outbox_pending", "bridge_outbox_oldest_pending_age_seconds",
                                                      "bridge_outbox_deadlettered_total", "resilience4j_circuitbreaker_state")))


if __name__ == "__main__":
    main()
