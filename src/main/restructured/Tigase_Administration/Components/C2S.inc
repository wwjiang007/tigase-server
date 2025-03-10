Client to Server Communication
------------------------------------------

Client to server communication is an integral part of XMPP communication. C2S handles all client communication to the server, and is responsible for filtering and handling remote communications. C2S CAN be disabled, however doing so will only allow communication of internal components, and S2S communications.

Configuration
^^^^^^^^^^^^^^^^

To disable C2S, use the following line in ``config.tdsl`` folder.

.. code::

   c2s (active: false) {}

Otherwise, C2S component is activated by default.

Connections
^^^^^^^^^^^^^^^^

The connections container houses all configuration related to connections with the component. Each port may be configured individually.

.. code::

   c2s {
       connections {
           5222 {
               <configuration>
           }
           5080 {
               <configuration>
           }
       }
   }

new-connections-throttling
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The property allows you to limit how many new users' connection per second the server accepts on a particular port. Connections established within the limit are processed normally, all others are simply disconnected. This allows you to avoid server overload in case there is a huge number of users trying to connect at the same time. Mostly this happens after a server restart.

.. code::

   c2s {
       connections {
           5222 {
               'new-connections-throttling' = 150L
           }
       }
   }

Here, this limits the number to 150 connections per second before connection attempts are dropped.

This replaces the old ``--new-connections-throttling`` property.

Resumption timeout
^^^^^^^^^^^^^^^^^^^^^^

It is now possible to set a default stream resumption timeout that the server uses. This allows control of how long a server will wait for a reconnection from a client. This can be particularly helpful to manage mobile clients connecting to your server as they may not have complete coverage, and you do not want to close the stream right away. By default, Tigase sets this value to 60 seconds.

.. code::

   c2s {
       'urn:xmpp:sm:3' {
           'resumption-timeout' = 90
       }
   }

This sets the default timeout to 90 seconds. You may, if you choose, specify a maximum timeout time, which will allow the server to wait between the default and maximum before a connection is closed.

.. code::

   c2s {
       'urn:xmpp:sm:3' {
           'max-resumption-timeout' = 900
       }
   }

.. Note::

   If the max-resumption-timeout is not set, it will always equal the resumption-timeout number, or default is none is set.

Available since v7.1.0

It is also possible to configure max queue size of unacknowledged packages, burst period, and burst period ratio limit. By default max queue size is set to ``2000``, burst period to ``30`` (seconds), and burst ratio to ``20``. That means that for we limit no. of unacked packages to ``2000``, but for a short period of time (``30`` seconds) we allow this queue to be ``20`` times larger, ie. to allow MAM packets burst during MAM sync to complete successfully without hitting queue size limit.

.. code::

   c2s {
       'urn:xmpp:sm:3' {
           'max-resumption-queue-size' = 2000
           'max-resumption-queue-size-burst-period' = 30
           'max-resumption-queue-size-burst-ratio' = 20
       }
   }

.. Note::

   If you wish to disable burst mechanism, please set burst ratio to ``0``.

Available since v8.5.0

Packet Redelivery
^^^^^^^^^^^^^^^^^^^^^

Normally packets are handled by C2S and are typically processed in the first run, however if that fails to send, a retry of sending that packet will occur after 60 seconds. If that second try fails, the delay will increase by a factor of 1.5. This means that the next retry will occur at 90, 135, and so on until the retry count is reached. By default this count is 15, however it can be changed by using the following setting:

.. code::

   c2s {
       'packet-deliver-retry-count' = '20'
   }

This setting prevents packet redelivery attempts from continuing into infinity (or when the host machine runs out of memory).