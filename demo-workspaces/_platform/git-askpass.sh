#!/bin/sh
case "$1" in
  *sername*) printf '%s\n' "$AGENT_GIT_USERNAME" ;;
  *) printf '%s\n' "$AGENT_GIT_TOKEN" ;;
esac
