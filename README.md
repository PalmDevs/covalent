<p align="center">
<img width="128px" height="128px" src="./assets/logo.jpg" style="border-radius: 100%" />
</p>
<h1 align="center">Covalent</h1>
<p align="center">An elegant Android modification framework for <a href="https://reactnative.dev">React Native</a> apps.</p>

## Features

> [!NOTE]  
> Covalent currently targets React Native 0.81.6, with Hermes V0 (VM-only) and New Architecture enabled.
>
> If you are looking to bump supported targets, please search for `@Target` comments.
> They indicate areas of the code that are likely to be affected by changes in React Native.

**Implemented**

- **Running custom Hermes bytecode**: Runs before the app's own bytecode runs
- **Bidirectional communication**: Allows custom JS to communicate with native and vice versa, without
  using fragile C++
  hooks. [More details here](./app/src/main/kotlin/me/palmdevs/covalent/tweaks/CovalentBridgeSupport.kt).
- **React Native DevSupport forced on**: LogBox on uncaught errors, ability to reload with double-R, packager connection
  open
- **Native hooking with C++**: JSI runtime is accessible, but is fragile and can break easily from app to app
  and time to time.

**Planned**

- Load custom scripts from the network
- JS part of the framework
- Development server for quick reloading of scripts
- Settings page for enabling/disabling features and modifying settings
- **Catalyst**: JS utilities, Metro module discovery, hooking functions, modifying UI, etc.
- React DevTools support

**Not Planned**

- **React Native DevTools support**: Requires recompilation of Hermes libraries and swapping in order to expose the
  Chrome DevTools protocol. This is out of the scope for this project, but I'm interested in working on it.
- **iOS support**: While possible, I don't have the resources to develop and test on iOS. However, I will gladly accept
  contributions for iOS if someone is interested in developing it.
- **Hermes V1 (Static Hermes) support**: This is simply out of the scope for this project.

## Documentation

Documentation won't be written until the framework is more stable and has more features.

The project structure should be easy to understand if you have an Android development background.
But regardless of your experience, feel free to ask if you're stuck.

## Purpose

Inspired by the works of [Revenge](https://github.com/revenge-mod) and its predecessors, I wanted to create a simple but
powerful framework for modifying *any* React Native apps.

But that is hard. React Native changes a lot, and every app is different. So I intend to make this framework a base for
other developers to build upon, and to make it as flexible as possible.

## License

Covalent will always be open source and free to use.

Since Covalent is based on the prior works of [Revenge](https://github.com/revenge-mod), it is also licensed under
the [GPL-3.0 License](./LICENSE).