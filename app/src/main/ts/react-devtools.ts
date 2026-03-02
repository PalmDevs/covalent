// @ts-expect-error
import * as rdt from '../../../../node_modules/react-devtools-core/backend'

rdt.initialize()

globalThis.__REACT_DEVTOOLS__ = {
	version: __RDT_VERSION__,
	exports: rdt,
}
