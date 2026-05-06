/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2021 RomRaider.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.romraider.maps;
import com.romraider.Settings;

public class Table1D extends Table {
    private static final long serialVersionUID = -8747180767803835631L;
    private Table axisParent = null;

    /**
     * Number of bytes to skip after each real data element when reading
     * axis data from ROM.  Mirrors the skipCells attribute already
     * supported on the parent Table3D body, but applied here to the
     * nested X-Axis / Y-Axis table elements.
     *
     * Example: for a ROM layout of [Data][Dummy][Data][Dummy]…
     * set skipCells="1" on the nested axis <table> element.
     */
    private int skipCells = 0;

    public int getSkipCells() {
        return skipCells;
    }

    public void setSkipCells(int skipCells) {
        this.skipCells = skipCells;
    }

    /**
     * Override populateTable so that, when skipCells > 0, each DataCell
     * receives an exact pre-computed byte offset instead of a logical index.
     * This eliminates the integer-division truncation that occurred when
     * (storageType + skipCells) did not divide evenly into storageType units.
     *
     * Example — storageType=1, skipCells=1 (ROM: [Data][Dummy][Data][Dummy]…):
     *   exactByteOffset = 0, 2, 4, 6 …  → reads at storageAddress+0, +2, +4, +6 ✓
     *
     * Note: getStorageType() must return a real integer byte width (1, 2, 4).
     * Float (sentinel 99) and MOVI20 (sentinel 20/28) axes with skipCells are
     * not a supported combination.
     */
    @Override
    public void populateTable(Rom rom) throws ArrayIndexOutOfBoundsException, IndexOutOfBoundsException {
        if (skipCells == 0) {
            // No skip – fall back to the standard (unchanged) base implementation.
            super.populateTable(rom);
            return;
        }

        if (isStaticDataTable()) return;
        validateScaling();

        boolean tempLock = locked;
        locked = false;

        if (!beforeRam) {
            this.ramOffset = rom.getRomID().getRamOffset();
        }

        for (int i = 0; i < data.length; i++) {
            // Exact byte distance from storageAddress to this cell — no division,
            // no truncation regardless of stride alignment.
            int exactByteOffset = i * (getStorageType() + skipCells);
            data[i] = new DataCell(this, i, exactByteOffset, rom, true);
        }

        locked = tempLock;
        calcCellRanges();
        addScale(new Scale());
    }

    @Override
    public TableType getType() {
        return TableType.TABLE_1D;
    }
    
    public void setAxisParent(Table axisParent) {
        this.axisParent = axisParent;
    }

    public Table getAxisParent() {
        return axisParent;
    }

    @Override
    public StringBuffer getTableAsString() {
        if(isStaticDataTable()) {
            StringBuffer output = new StringBuffer(Settings.BLANK);
            for (int i = 0; i < data.length; i++) {
                output.append(data[i].getStaticText());
                if (i < data.length - 1) {
                    output.append(Settings.TAB);
                }
            }
            return output;
        } else {
            return super.getTableAsString();
      
        }
    }
    
    @Override
    public double queryTable(Double input_x, Double input_y)  {
    	// No axis, so nothing to query
    	return 0;
    }
    
    @Override
    public void clearData() {
    	super.clearData();
    	axisParent = null;
    }

    @Override
    public String toString() {
        return super.toString() + " (1D)";
    }

    @Override
    public boolean isLiveDataSupported() {
        return false;
    }

    @Override
    public boolean isButtonSelected() {
        return true;
    }
    
    @Override
    public boolean equals(Object other) {
        try {
            if(null == other) {
                return false;
            }

            if(other == this) {
                return true;
            }

            if(!(other instanceof Table1D)) {
                return false;
            }

            Table1D otherTable = (Table1D)other;

            if(this.data.length != otherTable.data.length)
            {
                return false;
            }

            if(this.data.equals(otherTable.data))
            {
                return true;
            }

            // Compare Bin Values
            for(int i=0 ; i < this.data.length ; i++) {
                if(! this.data[i].equals(otherTable.data[i])) {
                    return false;
                }
            }

            return true;
        } catch(Exception ex) {
            // TODO: Log Exception.
            return false;
        }
    }
}

    
