.PHONY: install build test test-backend test-frontend run-backend run-frontend clean deploy-check db

install:
	cd backend-java && ./gradlew build -x test
	cd frontend && npm install && npx playwright install --with-deps chromium

test-backend:
	cd backend-java && ./gradlew clean test

test-frontend:
	cd frontend && npx playwright test

test: test-backend test-frontend

run-backend:
	cd backend-java && ./gradlew bootRun

run-frontend:
	cd frontend && npm run dev

build:
	cd backend-java && ./gradlew bootJar -x test
	cd frontend && npm run build

db:
	docker compose up -d postgres

deploy-check:
	cd backend-java && ./gradlew check
	cd frontend && npm run build

clean:
	cd backend-java && ./gradlew clean
	rm -rf frontend/dist frontend/node_modules
