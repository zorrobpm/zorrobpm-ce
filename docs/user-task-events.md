# User task events

The engine can publish every change of a user task to RabbitMQ, so that other systems (notifications,
task boards, analytics) learn about it without polling the REST API.

## Turning it on

| Property | Default | Meaning |
|---|---|---|
| `zorrobpm.events.user-task.enabled` | `false` | Write and publish the events |
| `zorrobpm.events.user-task.poll-interval` | `1s` | Pause between publication rounds |
| `zorrobpm.events.user-task.batch-size` | `100` | Events published per batch |
| `zorrobpm.events.user-task.confirm-timeout` | `10s` | How long to wait for the broker to confirm a batch |

In Docker Compose set `ZORROBPM_EVENTS_USER_TASK_ENABLED=true`. The broker is the one of
`spring.rabbitmq.*` (`RABBITMQ_HOST`, `RABBITMQ_PORT`, ...).

The events do not depend on the transport of service task jobs (`zorrobpm.transport`). On `grpc` with the
events on, the engine connects to RabbitMQ for the events only: the job queues are neither declared nor
listened to.

Only changes made while the flag is on produce events. Events still waiting when the flag is turned off stay
in the database and are published once it is turned on again.

## Queue

The engine declares the durable queue `zorrobpm.user-task-events` at start, when the broker is there, and
again before each batch it publishes, so the queue comes back after a broker reset or a deletion. Every event
of every type goes to this queue through the default exchange: there is no exchange and no routing key to
bind. Events wait in the queue until a consumer reads them, also when no consumer has connected yet.

| Event | When |
|---|---|
| `CREATED` | A user task is created, including each task of a multi-instance |
| `ASSIGNED` | An open task without an assignee is claimed |
| `UNASSIGNED` | The assignee of an open task is removed (unclaim) |
| `COMPLETED` | An open task is completed |
| `CANCELED` | The engine ends an open task without completion: an interrupting boundary timer or BPMN error, a multi-instance ending early or getting an incident, an interrupted call activity |

A rejected operation (claiming a task taken by someone else, a completion rejected by the output mapping)
produces no event. A task assigned in the model gets `CREATED` with the assignee and no `ASSIGNED`.

- **Picking types.** To handle only some events, read the AMQP `type` property of the message or the `type`
  field of the body and acknowledge the rest.
- **Growth.** The queue is not limited: read it, or turn the events off, so that it does not grow forever.
- **Several consumers.** Consumers of the one queue share its messages (competing consumers): each message
  goes to one of them, and the events of one task may then be handled out of order. For the order per task
  read the queue with a single consumer. To give the events to several independent systems, read the queue
  once and pass them on, for example with a shovel to an exchange of your own.
- **Declared by the engine.** Do not create the queue with other arguments (a quorum queue, a TTL, a length
  limit): the declaration of the engine then fails and the events wait in the database, with a warning in
  the log on each round.

## Moving from the topic exchange

Before this version the engine published to the topic exchange `zorrobpm.user-task-events` with the routing
keys `user-task.<type>` and declared no queue. Now it neither declares nor publishes to that exchange, and it
does not delete it or the queues bound to it.

1. Update the engine. New events go to the queue `zorrobpm.user-task-events` only.
2. Read what is left in your own queues bound to the exchange, then switch the consumers to the queue
   `zorrobpm.user-task-events`, picking types by `type` instead of the routing key.
3. Delete your old queues and the exchange `zorrobpm.user-task-events` by hand.

To roll back, run the former engine version: it publishes to the exchange again, and the events left in the
queue `zorrobpm.user-task-events` are read by hand.

## Message

Each event is one persistent message with `content_type = application/json`, `message_id` equal to the
`eventId` of the event, `type` equal to the event type, `timestamp` of the change and the header
`zorrobpm-user-task-id`. The body:

```json
{
  "eventId": "6f1c2b8e-2a7d-4c5e-9d3a-0b7f2c4e1a90",
  "type": "ASSIGNED",
  "occurredAt": "2026-10-02T10:20:00Z",
  "userTaskId": "0d2f3a4b-5c6d-4e7f-8a9b-0c1d2e3f4a5b",
  "bpmnElementId": "approve",
  "name": "Approve the order",
  "formKey": "approve-form",
  "assignee": "111",
  "candidateUsers": [],
  "candidateGroups": ["managers"],
  "createdAt": "2026-10-02T10:15:30Z",
  "completedAt": null,
  "canceledAt": null,
  "loopIndex": null,
  "loopTotal": null,
  "processInstanceId": "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d",
  "processDefinitionId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "processDefinitionKey": "order",
  "processDefinitionVersion": 2
}
```

- `assignee` is the assignee after the change, `null` when there is none.
- `completedAt` is set on `COMPLETED` only, `canceledAt` on `CANCELED` only.
- `loopIndex` and `loopTotal` are set on the tasks of a multi-instance.
- The fields describe the task at the moment of the change, not at the moment of publication.
- Variables are not part of the event: read them through the REST API or the tasklist by `userTaskId`.

Java consumers can read the body into `com.zorrodev.bpm.event.UserTaskLifecycleEvent` from
`zorrobpm-event`.

## Delivery guarantees

- **At least once.** An event is stored in the engine database in the transaction of the change and removed
  only after the broker confirmed its message. A command that rolls back leaves no event. If the broker is
  down, the engine keeps working and the events wait in the database until it is back, also across restarts.
  The same event may arrive more than once, always with the same `eventId`: deduplicate by it.
- **Order per task.** The events of one task are published in the order of its changes, also with several
  engine nodes and after a failed publication, and a single consumer of the queue receives them in this
  order. There is no order between the events of different tasks.
- **Retention.** While the broker is down the waiting events are not limited; each failed round is logged as
  a warning.
