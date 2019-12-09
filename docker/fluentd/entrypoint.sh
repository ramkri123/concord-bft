#!/bin/bash

FLUENTD_CONF_LOG_INTELLIGENCE="/fluentd/etc/fluent-log-intelligence.conf"
FLUENTD_CONF_LOG_INSIGHT="/fluentd/etc/fluent-log-insight.conf"
LOG_INTELLIGENCE="LOG_INTELLIGENCE"
LOG_INSIGHT="LOG_INSIGHT"

if [ "$LOG_DESTINATION" = $LOG_INTELLIGENCE ]; then
  # replace '/' in the URL with '\/' at every position, to match Fluentd plugin's expectations
  LINT_ENDPOINT_URL=$(echo $LINT_ENDPOINT_URL | sed 's/\//\\\//'g)
  sed -i 's/LINT_ENDPOINT_URL/'"$LINT_ENDPOINT_URL"'/g' $FLUENTD_CONF_LOG_INTELLIGENCE
  sed -i 's/LINT_AUTHORIZATION_BEARER/'"$LINT_AUTHORIZATION_BEARER"'/g' $FLUENTD_CONF_LOG_INTELLIGENCE
  export FLUENT_CONF=$FLUENTD_CONF_LOG_INTELLIGENCE
else
  sed -i 's/LOG_INSIGHT_HOST/'"$LOG_INSIGHT_HOST"'/g' $FLUENTD_CONF_LOG_INSIGHT
  sed -i 's/LOG_INSIGHT_PORT/'"$LOG_INSIGHT_PORT"'/g' $FLUENTD_CONF_LOG_INSIGHT
  sed -i 's/LOG_INSIGHT_USERNAME/'"$LOG_INSIGHT_USERNAME"'/g' $FLUENTD_CONF_LOG_INSIGHT
  sed -i 's/LOG_INSIGHT_PASSWORD/'"$LOG_INSIGHT_PASSWORD"'/g' $FLUENTD_CONF_LOG_INSIGHT
  sed -i 's/LOG_INSIGHT_AGENT_ID/'"$LOG_INSIGHT_AGENT_ID"'/g' $FLUENTD_CONF_LOG_INSIGHT
  export FLUENT_CONF=$FLUENTD_CONF_LOG_INSIGHT
fi

exec "$@"
