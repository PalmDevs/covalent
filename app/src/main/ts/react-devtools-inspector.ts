/**
 * Lets React DevTools select elements by tapping them, and highlight elements, in apps using production builds of React Native.
 * React Native's own overlay (ReactDevToolsOverlay) is only rendered in development builds.
 *
 * Covalent intercepts touches natively while inspecting, and draws highlights natively.
 *
 * Requires react-devtools.bundle to be loaded first.
 */

// @Target: Ported from https://github.com/react/react-native/blob/v0.86.3/packages/react-native/src/private/devsupport/devmenu/elementinspector/ReactDevToolsOverlay.js
// and getInspectorDataForViewAtPoint in https://github.com/react/react-native/blob/v0.86.3/packages/react-native/Libraries/Renderer/implementations/ReactFabric-dev.js
const HOST_COMPONENT = 5
const HIGHLIGHT_DURATION = 2000

type MeasureCallback = (
	x: number,
	y: number,
	width: number,
	height: number,
) => void

const hook = globalThis.__REACT_DEVTOOLS_GLOBAL_HOOK__
let agent: any = null
let hideTimeout: ReturnType<typeof setTimeout> | null = null

// Installed by Covalent's native code once the runtime exists
const host = () => globalThis.__covalentElementInspectorHost

hook.on('react-devtools', (newAgent: any) => {
	agent = newAgent

	agent.addListener('startInspectingNative', () => host()?.setInspecting(true))
	agent.addListener('stopInspectingNative', () => {
		host()?.setInspecting(false)
		host()?.highlight(null)
	})
	agent.addListener('showNativeHighlight', (elements: unknown[]) =>
		highlight(elements),
	)
	agent.addListener('hideNativeHighlight', () => host()?.highlight(null))
})

globalThis.__covalentElementInspector = {
	/**
	 * Called by Covalent with window coordinates (in dp) of a touch while inspecting.
	 */
	inspectAt(x: number, y: number, done: boolean) {
		const root = findRootNode()
		if (!agent || !root) return

		nativeFabricUIManager.measureInWindow(
			root,
			(rootX: number, rootY: number) =>
				nativeFabricUIManager.findNodeAtPoint(
					root,
					x - rootX,
					y - rootY,
					(internalInstanceHandle: any) => {
						const instance = internalInstanceHandle?.stateNode
						if (!instance?.node) return

						select(instance)
						highlight([instance])

						if (done) {
							agent.stopInspectingNative(true)
							host()?.setInspecting(false)

							if (hideTimeout !== null) clearTimeout(hideTimeout)
							hideTimeout = setTimeout(
								() => host()?.highlight(null),
								HIGHLIGHT_DURATION,
							)
						}
					},
				),
		)
	},
}

function findRootNode() {
	for (const id of hook.renderers.keys()) {
		for (const root of hook.getFiberRoots(id)) {
			let fiber = root.current.child
			while (fiber && fiber.tag !== HOST_COMPONENT) fiber = fiber.child
			if (fiber?.stateNode?.node) return fiber.stateNode.node
		}
	}

	return null
}

// React DevTools keys host instances by their public instance if one existed when they mounted, and by themselves otherwise
function select(instance: any) {
	for (const candidate of [instance.canonical?.publicInstance, instance]) {
		if (candidate != null && agent.getIDForHostInstance(candidate) != null)
			return agent.selectNode(candidate)
	}
}

function measureInWindow(instance: any, callback: MeasureCallback) {
	if (typeof instance?.measureInWindow === 'function')
		instance.measureInWindow(callback)
	else if (instance?.node)
		nativeFabricUIManager.measureInWindow(instance.node, callback)
	else return false

	return true
}

function highlight(instances: unknown[]) {
	const rects: number[] = []
	let pending = 0

	// Measuring may call back synchronously
	for (const instance of instances) {
		pending++
		const measuring = measureInWindow(instance, (x, y, width, height) => {
			rects.push(x, y, width, height)
			if (--pending === 0) host()?.highlight(rects)
		})

		if (!measuring) pending--
	}
}
