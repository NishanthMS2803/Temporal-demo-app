#!/bin/bash

echo "🧹 Cleaning up Temporal workflows..."
echo ""

# Go to parent directory where docker-compose.yml is
cd ..

# Stop Temporal
echo "Stopping Temporal server..."
docker-compose down

# Remove volumes
echo "Removing all workflow data..."
docker-compose down -v

echo ""
echo "✓ Temporal server cleaned!"
echo ""
echo "To start fresh, run:"
echo "  docker-compose up"
echo ""
