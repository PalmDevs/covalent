/**
 * Forwards console calls to React Native DevTools in apps that ship a fork of React Native.
 * React Native's own console forwarding is compiled out there, so Covalent's native code reports them to the Hermes CDP agent instead.
 * Evaluated by Covalent's native code (cpp/devtools.cpp) once `__covalentDevToolsConsole` is installed, before the app's bundle runs.
 */

// @Target: Matches facebook::hermes::cdp::ConsoleAPIType
const TYPES = new Map<PropertyKey, number>([
	['log', 0],
	['debug', 1],
	['info', 2],
	['error', 3],
	['warn', 4],
	['dir', 5],
	['dirxml', 6],
	['table', 7],
	['trace', 8],
	['group', 9],
	['groupCollapsed', 10],
	['groupEnd', 11],
	['clear', 12],
	['assert', 13],
	['timeEnd', 14],
	['count', 15],
])

type Method = (...args: unknown[]) => unknown

const wrapped = new WeakSet<object>()

// Apps wrap console methods and call the previous ones, which are wrapped too. Only the outermost call is reported.
let reporting = false

function report(key: PropertyKey, args: unknown[]) {
	const reportConsole = globalThis.__covalentDevToolsConsole
	if (!reportConsole) return

	const type = TYPES.get(key)!
	if (key !== 'assert') reportConsole(type, ...args)
	else if (!args[0]) reportConsole(type, ...args.slice(1))
}

function wrap(target: unknown) {
	if (target == null || typeof target !== 'object' || wrapped.has(target))
		return target

	const cache = new Map<PropertyKey, { original: Method; wrapper: Method }>()
	const proxy = new Proxy(target, {
		get(target, key, receiver) {
			const value = Reflect.get(target, key, receiver)
			if (typeof value !== 'function' || !TYPES.has(key)) return value

			const cached = cache.get(key)
			if (cached?.original === value) return cached!.wrapper

			const wrapper: Method = function (this: unknown, ...args) {
				if (reporting) return value.apply(this, args)

				try {
					report(key, args)
				} catch {}

				reporting = true
				try {
					return value.apply(this, args)
				} finally {
					reporting = false
				}
			}

			cache.set(key, { original: value, wrapper })
			return wrapper
		},
	})

	wrapped.add(proxy)
	return proxy
}

// The app's bundle replaces `console` while it initializes, so replacements are wrapped as well
let current = wrap(globalThis.console)

Object.defineProperty(globalThis, 'console', {
	configurable: true,
	get: () => current,
	set: value => {
		current = wrap(value)
	},
})
