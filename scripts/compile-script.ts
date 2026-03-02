import { main } from 'bun'
import chalk from 'chalk'
import { readdirSync } from 'fs'
import { join } from 'path'
import { rolldown } from 'rolldown'
import rdtPkg from '../node_modules/react-devtools-core/package.json'
import hermesSwcPlugin from './plugins/hermes-swc'
import hermesCPlugin from './plugins/hermesc'
import shimAliases from './plugins/shim-aliases'

const OUT_DIR = join(import.meta.dir, '../app/src/main/assets')
const BASE_DIR = join(import.meta.dir, '../app/src/main/ts')
const SHIMS_DIR = `${BASE_DIR}/shims`

const filePath = process.argv[2]?.trim()
if (!filePath) {
	console.error(
		chalk.redBright('✖ Please provide the file to compile as an argument.'),
	)

	process.exit(1)
}

const filePaths =
	filePath === '*'
		? readdirSync(BASE_DIR).filter(f => f.endsWith('.ts'))
		: [filePath]

const __DEV__ =
	process.argv.includes('--dev') || process.env.NODE_ENV === 'development'

// If this file is being run directly, build the project
if (main === import.meta.filename) {
	for (const path of filePaths) {
		await build(path, __DEV__, true)
	}
}
export default async function build(
	filePath: string,
	dev = __DEV__,
	log = true,
) {
	const start = performance.now()

	if (log) console.info(chalk.gray('\u{1F5CE} Compiling JS...'))

	const bundle = await rolldown({
		input: join(BASE_DIR, filePath),
		platform: 'neutral',
		external: [/^node:/],
		preserveEntrySignatures: false,
		transform: {
			define: {
				__RDT_VERSION__: JSON.stringify(rdtPkg.version),
			},
		},
		tsconfig: 'tsconfig.json',
		treeshake: true,
		plugins: [
			shimAliases(SHIMS_DIR),
			hermesSwcPlugin(),
			hermesCPlugin({
				flags: [
					dev ? '-Og' : '-O',
					dev ? '-g3' : '-g1',
					'-reuse-prop-cache',
					'-optimized-eval',
					'-strict',
					'-finline',
				],
				before(ver) {
					if (log) {
						console.debug(
							chalk.cyanBright('\u{1F5CE} JS compilation finished...'),
						)

						console.debug(
							chalk.gray(`\u{1F5CE} Compiling bytecode with ${ver}...`),
						)
					}
				},
				after() {
					if (log)
						console.debug(
							chalk.cyanBright('\u{1F5CE} Bytecode compilation finished'),
						)
				},
			}),
		],
	})

	const outFileName = `${filePath.replace(/\.ts$/, '')}.bundle`

	await bundle.write({
		minify: 'dce-only',
		esModule: false,
		minifyInternalExports: true,
		file: `${OUT_DIR}/${outFileName}`,
		format: 'iife',
		postFooter: `//# sourceURL=covalent://assets/${outFileName}`,
		keepNames: true,
	})

	if (log)
		console.info(
			chalk.greenBright(
				`\u{2714} Compiled successfully! ${chalk.gray(`(took ${(performance.now() - start).toFixed(2)}ms)`)}`,
			),
		)
}
