FROM rabbitmq:3.13-management

COPY rabbitmq_delayed_message_exchange-3.13.0.ez /opt/rabbitmq/plugins/
COPY definitions.json /etc/rabbitmq/
COPY plugins/enabled_plugins.conf /etc/rabbitmq/enabled_plugins
COPY rabbitmq.conf /etc/rabbitmq/rabbitmq.conf