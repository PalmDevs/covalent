// Connects React DevTools to the Components and Profiler panels of React Native DevTools (Fusebox).
// React Native only sets this up in development builds, see Libraries/Core/setUpReactDevTools.js.
// Requires react-devtools.bundle to be loaded first.

// @Target: Keep in sync with https://github.com/react/react-native/blob/v0.86.3/packages/react-native/src/private/devsupport/rndevtools/setUpFuseboxReactDevToolsDispatcher.js
const BINDING_NAME = '__CHROME_DEVTOOLS_FRONTEND_BINDING__'

type Listener<T> = (value: T) => void

class EventScope<T> {
	#listeners = new Set<Listener<T>>()

	addEventListener(listener: Listener<T>) {
		this.#listeners.add(listener)
	}

	removeEventListener(listener: Listener<T>) {
		this.#listeners.delete(listener)
	}

	emit(value: T) {
		for (const listener of this.#listeners) listener(value)
	}
}

class Domain {
	name: string
	onMessage = new EventScope<unknown>()

	constructor(name: string) {
		// The frontend adds the binding before initializing domains
		if ((globalThis as any)[BINDING_NAME] == null)
			throw new Error(
				`Could not create domain ${name}: receiving end doesn't exist`,
			)

		this.name = name
	}

	sendMessage(message: unknown) {
		;(globalThis as any)[BINDING_NAME](
			JSON.stringify({ domain: this.name, message }),
		)
	}
}

const domains = new Map<string, Domain>()

const FuseboxReactDevToolsDispatcher = {
	BINDING_NAME,
	onDomainInitialization: new EventScope<Domain>(),

	initializeDomain(name: string) {
		const domain = new Domain(name)
		domains.set(name, domain)
		this.onDomainInitialization.emit(domain)
		return domain
	},

	sendMessage(name: string, message: string) {
		const domain = domains.get(name)
		if (!domain)
			throw new Error(`Could not send message to ${name}: domain doesn't exist`)

		domain.onMessage.emit(JSON.parse(message))
	},
}

Object.defineProperty(globalThis, '__FUSEBOX_REACT_DEVTOOLS_DISPATCHER__', {
	value: FuseboxReactDevToolsDispatcher,
	configurable: false,
	enumerable: false,
	writable: false,
})

let disconnect: (() => void) | null | undefined = null

FuseboxReactDevToolsDispatcher.onDomainInitialization.addEventListener(
	domain => {
		if (domain.name !== 'react-devtools') return

		// A new frontend session initializes the domain again
		disconnect?.()
		disconnect = __REACT_DEVTOOLS__.exports.connectWithCustomMessagingProtocol({
			onSubscribe: (listener: Listener<unknown>) =>
				domain.onMessage.addEventListener(listener),
			onUnsubscribe: (listener: Listener<unknown>) =>
				domain.onMessage.removeEventListener(listener),
			onMessage: (event: string, payload: unknown) =>
				domain.sendMessage({ event, payload }),
		})
	},
)
