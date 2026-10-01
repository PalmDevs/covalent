// Lets React DevTools edit props and hook state in apps using production React renderers.

// Production renderers don't provide the functions React DevTools calls to edit values, so they're provided here.
// Requires react-devtools.bundle to be loaded first, and must run before React registers its renderer.

// @Target: Ported from React 19.2.3 (https://github.com/react/react-native/blob/v0.86.3/packages/react-native/Libraries/Renderer/implementations/ReactFabric-dev.js).
// These rely on the same Fiber internals React DevTools itself relies on.
const CLASS_COMPONENT = 1

type Path = Array<string | number>
type Updater = { enqueueForceUpdate(instance: unknown): void }

const hook = globalThis.__REACT_DEVTOOLS_GLOBAL_HOOK__
const inject = hook.inject

hook.inject = function (renderer: any) {
	// Development renderers provide these themselves
	if (typeof renderer.overrideProps === 'function')
		return inject.call(this, renderer)

	let id: number
	Object.assign(
		renderer,
		createOverrides(() => id),
	)
	id = inject.call(this, renderer)
	return id
}

function createOverrides(getRendererId: () => number) {
	let classUpdater: Updater | null = null

	// All class components share React's class updater, which is the only way to schedule an update on an arbitrary Fiber in production builds
	function findClassUpdater(): Updater | null {
		if (classUpdater) return classUpdater

		for (const root of hook.getFiberRoots(getRendererId())) {
			const stack = [root.current]
			while (stack.length) {
				const fiber = stack.pop()
				const updater = fiber.stateNode?.updater
				if (
					fiber.tag === CLASS_COMPONENT &&
					typeof updater?.enqueueForceUpdate === 'function'
				)
					return (classUpdater = updater)

				if (fiber.sibling) stack.push(fiber.sibling)
				if (fiber.child) stack.push(fiber.child)
			}
		}

		return null
	}

	function scheduleUpdate(fiber: any) {
		const updater = findClassUpdater()
		if (!updater)
			return console.warn(
				'[Covalent] Could not find a class component to schedule updates with, React DevTools edits will not apply',
			)

		if (fiber.tag === CLASS_COMPONENT)
			return updater.enqueueForceUpdate(fiber.stateNode)

		// The class updater queues a force update on fiber.updateQueue.shared, which only class components have.
		// A throwaway queue receives it instead. The update itself is never processed, it only marks the Fiber for rendering.
		const updateQueue = fiber.updateQueue
		fiber.updateQueue = {
			shared: { pending: null, lanes: 0, hiddenCallbacks: null },
		}
		try {
			updater.enqueueForceUpdate({ _reactInternals: fiber })
		} finally {
			fiber.updateQueue = updateQueue
		}
	}

	function findHook(fiber: any, id: number) {
		let hook = fiber.memoizedState
		while (hook !== null && id > 0) {
			hook = hook.next
			id--
		}
		return hook
	}

	function setHookState(fiber: any, id: number, update: (state: any) => any) {
		const hook = findHook(fiber, id)
		if (hook === null) return

		hook.memoizedState = hook.baseState = update(hook.memoizedState)
		// A new props object keeps React from bailing out of rendering the Fiber
		fiber.memoizedProps = { ...fiber.memoizedProps }
		scheduleUpdate(fiber)
	}

	function setProps(fiber: any, props: any) {
		fiber.pendingProps = props
		if (fiber.alternate) fiber.alternate.pendingProps = props
		scheduleUpdate(fiber)
	}

	return {
		overrideHookState: (fiber: any, id: number, path: Path, value: unknown) =>
			setHookState(fiber, id, state => copyWithSet(state, path, 0, value)),
		overrideHookStateDeletePath: (fiber: any, id: number, path: Path) =>
			setHookState(fiber, id, state => copyWithDelete(state, path, 0)),
		overrideHookStateRenamePath: (
			fiber: any,
			id: number,
			oldPath: Path,
			newPath: Path,
		) =>
			setHookState(fiber, id, state =>
				copyWithRename(state, oldPath, newPath, 0),
			),
		overrideProps: (fiber: any, path: Path, value: unknown) =>
			setProps(fiber, copyWithSet(fiber.memoizedProps, path, 0, value)),
		overridePropsDeletePath: (fiber: any, path: Path) =>
			setProps(fiber, copyWithDelete(fiber.memoizedProps, path, 0)),
		overridePropsRenamePath: (fiber: any, oldPath: Path, newPath: Path) =>
			setProps(fiber, copyWithRename(fiber.memoizedProps, oldPath, newPath, 0)),
		scheduleUpdate,
	}
}

function shallowCopy(obj: any) {
	return Array.isArray(obj) ? obj.slice() : { ...obj }
}

function copyWithSet(obj: any, path: Path, index: number, value: unknown): any {
	if (index >= path.length) return value

	const key = path[index]!
	const updated = shallowCopy(obj)
	updated[key] = copyWithSet(obj[key], path, index + 1, value)
	return updated
}

function copyWithDelete(obj: any, path: Path, index: number): any {
	const key = path[index]!
	const updated = shallowCopy(obj)

	if (index + 1 === path.length) {
		if (Array.isArray(updated)) updated.splice(key as number, 1)
		else delete updated[key]
		return updated
	}

	updated[key] = copyWithDelete(obj[key], path, index + 1)
	return updated
}

// DevTools only renames the deepest key, the rest of the paths are the same
function copyWithRename(
	obj: any,
	oldPath: Path,
	newPath: Path,
	index: number,
): any {
	const oldKey = oldPath[index]!
	const updated = shallowCopy(obj)

	if (index + 1 === oldPath.length) {
		updated[newPath[index]!] = updated[oldKey]
		if (Array.isArray(updated)) updated.splice(oldKey as number, 1)
		else delete updated[oldKey]
	} else {
		updated[oldKey] = copyWithRename(obj[oldKey], oldPath, newPath, index + 1)
	}

	return updated
}
