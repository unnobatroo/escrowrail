PYTHON ?= .venv/bin/python
PIP ?= .venv/bin/pip

.PHONY: install lint typecheck test test-invariants test-concurrency verify-pr check smoke-test seed run security coverage test-mutation

install:
	$(PIP) install -e ".[dev,test]"

lint:
	.venv/bin/ruff check .
	.venv/bin/ruff format --check .

typecheck:
	.venv/bin/mypy --strict .

test:
	$(PYTHON) -m pytest -v --tb=short --ignore=tests/test_invariants.py --ignore=tests/test_concurrency_and_load.py --ignore=tests/test_synthetic_traffic.py

test-invariants:
	$(PYTHON) -m pytest -v --tb=short tests/test_invariants.py

test-concurrency:
	$(PYTHON) -m pytest -v --tb=short tests/test_concurrency_and_load.py tests/test_synthetic_traffic.py

security:
	.venv/bin/bandit -r app/ -ll -q

coverage:
	$(PYTHON) -m pytest --cov=app --cov-report=term-missing --cov-fail-under=90 -q

test-mutation:
	.venv/bin/mutmut run || true

verify-pr: lint typecheck security test test-invariants test-concurrency

check: verify-pr

smoke-test:
	$(PYTHON) scripts/smoke_test.py

seed:
	$(PYTHON) scripts/seed_demo_data.py

run:
	.venv/bin/uvicorn app.main:app --reload
