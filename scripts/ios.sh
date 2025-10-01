#!/bin/bash
LIP=$(ipconfig getifaddr en0)

echo "🍦 Starting local development to ios device - ensure local dev server is running already"
echo "🏗️ Type checking and building for development..."
pnpm run build:dev
echo "🔃 Capacitor installation, podfile installation, sync and copy to app distribution folders..."
npx @ionic/cli capacitor sync ios --no-build
echo "🏃 Select an iOS device to run the build at local ip address ${LIP} on..."
eval "npx @ionic/cli capacitor run ios --livereload-url=http://${LIP}:3000  --external --mode development"
