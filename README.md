# ScatterRoutingService

ScatterRoutingService is a reference implementation of a Scatterbrain router for
android phones.

## What is Scatterbrain

Scatterbrain is a protocol for operating a delay tolerant radio network across
multiple transport protocols. Basically, a Scatterbrain router manages
various wireless protocols to send messages from device to device in a
store-and-forward fashion to allow communication over long distance.

This differs from normal wireless networks in a few key ways.

- there is no need for a persistent connection from point A to point B.
each router only cares about exchanging messages with nearby routers.  
- routers are stateful and will store as many messages as they are able
in order to forward messages to routers that may come within range in the
future.  
- The network depends on routers being in motion. i.e. phones in people's
pockets  
- messages are not guaranteed to reach their destination, but still have
a large chance of reaching many devices/users

It is a good analogy to think of this paradigm as simulating the spread of
gossip or rumors though society. Any people you pass on the street may
be helping your network traffic spread to new users. This concept is called
"delay tolerant networking"

Scatterbrain aims to generalize delay tolerant networking by allowing devices
to forward traffic in a protocol agnostic way, potentially using multiple
transport layers at once to achieve maximum coverage and throughput.

## How do I use it?

By itself ScatterRoutingService doesn't really do anything except expose an api
for 3rd party apps to use. If you are a developer you might want to check out
[this sdk](https://github.com/Scatterbrain-DTN/ScatterbrainSDK) to start writing
apps using the Scatterbrain API.

Scatterbrain is still under heavy development, so expect some bugs. If you want to
help out feel free to make an issue or PR and I will get back to you as soon as
I can. Contributions of any kind are always welcome.

## Where can I get the app?

This repo is just a library for the Scatterbrain backend. If you want an installable
production app check out
[RoutingServiceFrontend](https://github.com/Scatterbrain-DTN/RoutingServiceFrontend)

## What works

- Bluetooth LE transport for device discovery and data transfer  
- Wifi direct transport for data transfer only  
- Bootstrapping between transports (i.e. using bluetooth for discovering
devices and wifi for transferring data)  
- Cryptographic identities for signing messages  
- Various battery saving profiles, including bluetooth LE offloaded
scanning for discovering nearby devices from an idle phone  
- All basic API features, mainly sending and receiving messages and managing router
state


## What is planned for the future

- Optional advanced "social" routing algorithms for directing messages based on
 location  
- Datastore pruning  
- Antiflood/antispam based on time or location  
- Downloadable plugins for new transport layers (possibly nfc, ultrasound, or
USB)  
- Better api wrapper using kotlin coroutines and/or rxjava2  
- Linux/raspberrypi version for stationary routers  

