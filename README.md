Here's a comprehensive GitHub README for your AutoClutch module:

# AutoClutch Module

An intelligent ender pearl clutch module that uses simulated annealing to find optimal throwing angles for survival in dangerous situations.

## Features

- **Advanced trajectory calculation** using simulated annealing algorithm
- **Void detection and evasion** with configurable void level
- **Safety checks** for landing positions and headspace
- **Real-time visualization** of predicted trajectories
- **Combat integration** with optional combat-only activation
- **Performance optimization** with caching and background calculations

## Configuration Options

### Algorithm Settings
| Option | Description | Default | Range |
|--------|-------------|---------|-------|
| Algorithm | Optimization algorithm to use | SimulatedAnnealing | SimulatedAnnealing |
| MaxIterations | Maximum iterations for the algorithm | 500 | 50-10000 |
| StagnationLimit | Iterations without improvement before reset | 2333 | 1000-10000 |
| IterationsSpeed | Speed of iteration processing | 5 | 1-50 |
| InitialTemp | Initial temperature for simulated annealing | 20 | 5-50 |
| MinTemperature | Minimum temperature threshold | 0.01 | 0.01-0.1 |
| CoolingFactor | Temperature reduction factor | 0.97 | 0.95-0.99 |
| MaxCacheSize | Maximum positions to cache | 1337 | 500-1500 |

### Aiming Settings
| Option | Description | Default | Range |
|--------|-------------|---------|-------|
| AimPrecision | Required precision before throwing (degrees) | 0.1 | 0.1-1 |
| PitchLimit | Allowed pitch range for throws | -90..0 | -90..45 |
| AdjacentSafeBlocks | Required safe blocks around landing | 0 | 0-3 |

### Timing Settings
| Option | Description | Default | Range |
|--------|-------------|---------|-------|
| SimulationTime | Ticks to simulate player trajectory | 30 | 30-50 |
| Cooldown | Ticks between pearl throws | 0 | 0-20 |
| VoidEvasionFrequency | Void check frequency | 14 | 5-[refresh rate] |

### Safety Settings
| Option | Description | Default |
|--------|-------------|---------|
| VoidLevel | Y-level considered as void | 0 |
| UnsafeBlocks | Blocks considered dangerous | [Water, Lava, etc.] |
| EnsureHeadSpace | Check for 2-block headroom | true |
| AllowClutchWithStuck | Work with AutoStuck module | true |
| OnlyDuringCombat | Only activate during combat | false |

## Usage

1. Ensure you have ender pearls in your hotbar or offhand
2. Enable the module
3. The module will automatically:
   - Detect when you're in danger
   - Calculate optimal throw angles
   - Rotate and throw pearls when needed

## Visual Indicators

- **Green trajectory**: Safe predicted path
- **Red trajectory**: Dangerous path
- **Green landing marker**: Safe landing position
- **Red landing marker**: Unsafe landing position

## Technical Details

The module uses:
- Simulated annealing for optimization
- Player trajectory prediction
- Collision detection with blocks and entities
- Caching for performance optimization
- Background thread calculations to prevent lag spikes
