/*
 * RomRaider Open-Source Tuning, Logging and Reflashing
 * Copyright (C) 2006-2022 RomRaider.com
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

import java.io.Serializable;
import java.text.ParseException;
import java.util.LinkedList;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;

import com.romraider.Settings;
import com.romraider.Settings.Endian;
import com.romraider.editor.ecu.ECUEditorManager;
import com.romraider.util.ByteUtil;
import com.romraider.util.JEPUtil;
import com.romraider.util.NumberUtil;
import com.romraider.util.SettingsManager;
import com.romraider.xml.RomAttributeParser;

public class DataCell implements Serializable  {
    private static final long serialVersionUID = 1111479947434817639L;
    private static final Logger LOGGER = Logger.getLogger(DataCell.class);

    //View we need to keep up to date
    private DataCellView view = null;
    private Table table;

    //This sounds like a View property, but the manipulation
    //functions depend on this, so its better to put it here
    private boolean isSelected = false;

    private int bitMask = 0;
    private double minAllowedBin = 0.0;
    private double maxAllowedBin = 0.0;
    private double binValue = 0.0;
    private double originalValue = 0.0;
    private double compareToValue = 0.0;
    private String liveValue = Settings.BLANK;
    private String staticText = null;
    private Rom rom;

    // Sequential grid/UI slot number (0, 1, 2 …). Always contiguous regardless
    // of skipCells. Preserved for API compat via getIndexInTable().
    private int index;

    // Same as index for standard tables; the sequential UI slot for skip-cell
    // tables. Used by Bosch-subtract loop bounds and neighbour references.
    private int logicalIndex;

    // Exact byte distance from storageAddress to this cell's first byte.
    // Replaces all "index * storageType" arithmetic so skipCells strides are
    // exact even when (storageType + skipCells) doesn't divide evenly.
    private int byteOffset;

    public DataCell(Table table, Rom rom) {
        this.table = table;
        this.rom = rom;
        setBitMask(table.getBitMask()); //Take the global bitmask first
    }

    public DataCell(Table table, String staticText, Rom rom) {
        this(table, rom);
        final StringTokenizer st = new StringTokenizer(staticText, DataCellView.ST_DELIMITER);
        if (st.hasMoreTokens()) {
            this.staticText = st.nextToken();
        }
    }

    public DataCell(Table table, int index, Rom rom) {
        this(table, rom);
        this.index = index;
        this.logicalIndex = index;

        // Resolve the physical byte width for special storage-type sentinels.
        int st = table.getStorageType();
        int physicalWidth;
        if (st == Settings.STORAGE_TYPE_FLOAT) {
            physicalWidth = 4;
        } else if (st == Settings.STORAGE_TYPE_MOVI20 || st == Settings.STORAGE_TYPE_MOVI20S) {
            physicalWidth = 3;
        } else {
            physicalWidth = st;
        }
        this.byteOffset = index * physicalWidth;

        updateBinValueFromMemory();
        this.originalValue = this.binValue;
        registerDataCell(this);
    }

    /**
     * Constructor for tables with skipCells > 0: the caller supplies the
     * exact pre-computed byte offset so no integer-division truncation occurs.
     *
     * @param logicalIndex      sequential UI slot (0, 1, 2 …)
     * @param byteOffset        exact byte distance from storageAddress to this cell
     * @param isAbsoluteOffset  must be {@code true}; signals that byteOffset is
     *                          already a physical byte distance, not a cell index
     */
    public DataCell(Table table, int logicalIndex, int byteOffset, Rom rom, boolean isAbsoluteOffset) {
        this(table, rom);
        this.logicalIndex = logicalIndex;
        this.index = logicalIndex;
        this.byteOffset = byteOffset;

        updateBinValueFromMemory();
        this.originalValue = this.binValue;
        registerDataCell(this);
    }

    public int getLogicalIndex() {
        return logicalIndex;
    }

    public int getByteOffset() {
        return byteOffset;
    }

    public void setTable(Table t) {
        this.table = t;
    }

    public void setRom(Rom rom) {
        this.rom = rom;
    }

    public byte[] getBinary() {
        return rom.getBinary();
    }

    public void setBitMask(int mask) {
        if (mask == 0) return;

        //Clamp mask to max size
        bitMask = (int) Math.min(mask, Math.pow(2,table.getStorageType()*8)-1);
    }

    protected void calcValueRange() {
        if (table.getStorageType() != Settings.STORAGE_TYPE_FLOAT) {
            if (table.isSignedData()) {
                switch (table.getStorageType()) {
                case 1:
                    minAllowedBin = Byte.MIN_VALUE;
                    maxAllowedBin = Byte.MAX_VALUE;
                    break;
                case 2:
                    minAllowedBin = Short.MIN_VALUE;
                    maxAllowedBin = Short.MAX_VALUE;
                    break;
                case 4:
                    minAllowedBin = Integer.MIN_VALUE;
                    maxAllowedBin = Integer.MAX_VALUE;
                    break;
                case Settings.STORAGE_TYPE_MOVI20:
                    minAllowedBin = Settings.MOVI20_MIN_VALUE;
                    maxAllowedBin = Settings.MOVI20_MAX_VALUE;
                    break;
                case Settings.STORAGE_TYPE_MOVI20S:
                    minAllowedBin = Settings.MOVI20S_MIN_VALUE;
                    maxAllowedBin = Settings.MOVI20S_MAX_VALUE;
                    break;
                }
            }
            else {
                if (bitMask == 0) {
                    maxAllowedBin = (Math.pow(256, table.getStorageType()) - 1);
                }
                else {
                    maxAllowedBin =(int)(Math.pow(2,ByteUtil.lengthOfMask(bitMask)) - 1);
                }
                minAllowedBin = 0.0;
            }
        } else {
            maxAllowedBin = Float.MAX_VALUE;

            if (table.isSignedData()) {
                minAllowedBin = 0.0;
            } else {
                minAllowedBin = -Float.MAX_VALUE;
            }
        }
    }

    private double getValueAtByteOffset(int targetByteOffset) {
        double dataValue = 0.0;
        byte[] input = getBinary();
        int storageType = table.getStorageType();
        Endian endian = table.getEndian();
        int ramOffset = table.getRamOffset();
        int storageAddress = table.getStorageAddress();
        boolean signed = table.isSignedData();

        if (storageType == Settings.STORAGE_TYPE_FLOAT) {
            byte[] byteValue = new byte[4];
            byteValue[0] = input[storageAddress + targetByteOffset - ramOffset];
            byteValue[1] = input[storageAddress + targetByteOffset - ramOffset + 1];
            byteValue[2] = input[storageAddress + targetByteOffset - ramOffset + 2];
            byteValue[3] = input[storageAddress + targetByteOffset - ramOffset + 3];
            dataValue = RomAttributeParser.byteToFloat(byteValue, table.getEndian(), table.getMemModelEndian());

        } else if (storageType == Settings.STORAGE_TYPE_MOVI20 ||
                storageType == Settings.STORAGE_TYPE_MOVI20S) {
            dataValue = RomAttributeParser.parseByteValue(input,
                    endian,
                    storageAddress + targetByteOffset - ramOffset,
                    storageType,
                    signed);

        } else {
            if (bitMask == 0) {
                dataValue = RomAttributeParser.parseByteValue(input,
                        endian, storageAddress + targetByteOffset - ramOffset,
                        storageType, signed);
            } else {
                dataValue = RomAttributeParser.parseByteValueMasked(input, endian,
                        storageAddress + targetByteOffset - ramOffset,
                        storageType, signed, bitMask);
            }
        }

        return dataValue;
    }

    private double getValueFromMemory() {
        if (table.getDataLayout() == Table.DataLayout.BOSCH_SUBTRACT) {

            // Bosch Motronic subtract method: iterate by logical index to stay
            // within bounds; fetch each cell's value via its own byteOffset.
            double dataValue = Math.pow(2, 8 * table.getStorageType());

            for (int j = table.data.length - 1; j >= logicalIndex; j--) {
                dataValue -= getValueAtByteOffset(table.data[j].getByteOffset());
            }

            return dataValue;
        } else {
            return getValueAtByteOffset(this.byteOffset);
        }
    }

    public void saveBinValueInFile() {
        if (table.getName().contains("Checksum Fix")) return;

        byte[] binData = getBinary();
        int userLevel = table.getUserLevel();
        int storageType = table.getStorageType();
        Endian endian = table.getEndian();
        int ramOffset = table.getRamOffset();
        int storageAddress = table.getStorageAddress();
        boolean isBoschSubtract = table.getDataLayout() == Table.DataLayout.BOSCH_SUBTRACT;

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("saveBinValueInFile: table=" + table.getName()
                    + " cell=" + logicalIndex + " byteOffset=" + byteOffset
                    + " addr=" + (storageAddress + byteOffset - ramOffset)
                    + " binValue=" + binValue
                    + " userLevel=" + userLevel + " settingsUserLevel=" + getSettings().getUserLevel()
                    + " binDataLen=" + (binData != null ? binData.length : "null"));

        double crossedValue = 0;

        //Do reverse cross referencing in for Bosch Subtract Axis array
        if (isBoschSubtract) {
            for (int i = table.data.length - 1; i >= logicalIndex; i--) {
                if (i == logicalIndex)
                    crossedValue -= table.data[i].getBinValue();
                else if (i == table.data.length - 1)
                    crossedValue = Math.pow(2, 8 * storageType) - getValueAtByteOffset(table.data[i].getByteOffset());
                else {
                    crossedValue -= getValueAtByteOffset(table.data[i].getByteOffset());
                }
            }
        }

        if (userLevel > getSettings().getUserLevel() || (userLevel >= 5 && !getSettings().isSaveDebugTables())) {
            LOGGER.warn("saveBinValueInFile: BLOCKED by userLevel check — tableUserLevel=" + userLevel
                    + " settingsUserLevel=" + getSettings().getUserLevel()
                    + " isSaveDebugTables=" + getSettings().isSaveDebugTables()
                    + " table=" + table.getName() + " cell=" + logicalIndex);
        }
        if (userLevel <= getSettings().getUserLevel() && (userLevel < 5 || getSettings().isSaveDebugTables()) ) {
                // determine output byte values
                byte[] output;
                int mask = bitMask;

                if (storageType != Settings.STORAGE_TYPE_FLOAT) {
                    int finalValue = 0;

                    // convert byte values
                    if (table.isStaticDataTable() && storageType > 0) {
                        LOGGER.warn("Static data table: " + table.toString() + ", storageType: "+storageType);

                        try {
                            finalValue = Integer.parseInt(getStaticText());
                        } catch (NumberFormatException ex) {
                            LOGGER.error("Error parsing static data table value: " + getStaticText(), ex);
                            LOGGER.error("Validate the table definition storageType and data value.");
                            return;
                        }
                    } else if (table.isStaticDataTable() && storageType < 1) {
                        // Do not save the value.
                        //if (LOGGER.isDebugEnabled())
                        //    LOGGER.debug("The static data table value will not be saved.");
                        return;
                    }  else {
                        finalValue = (int) (isBoschSubtract ? crossedValue : getBinValue());
                    }

                    if (mask != 0) {
                        // Shift left again
                        finalValue = finalValue << ByteUtil.firstOneOfMask(mask);
                    }

                    output = RomAttributeParser.parseIntegerValue(finalValue, endian, storageType);

                    int byteLength = storageType;
                    if (storageType == Settings.STORAGE_TYPE_MOVI20 ||
                            storageType == Settings.STORAGE_TYPE_MOVI20S) { // when data is in MOVI20 instruction
                        byteLength = 3;
                    }

                    //If mask enabled, only change bits within the mask
                    if (mask != 0) {
                        int tempBitMask = 0;

                        for (int z = 0; z < byteLength; z++) { // insert into file

                            tempBitMask = mask;

                            //Trim mask depending on byte, from left to right
                            tempBitMask = (tempBitMask & (0xFF << 8 * (byteLength - 1 - z))) >> 8*(byteLength - 1 - z);

                            // Delete old bits
                            binData[this.byteOffset + z + storageAddress - ramOffset] &= ~tempBitMask;

                            // Overwrite
                            binData[this.byteOffset + z + storageAddress - ramOffset] |= output[z];
                        }
                    }
                    //No Masking
                    else {
                        for (int z = 0; z < byteLength; z++) { // insert into file
                            binData[this.byteOffset + z + storageAddress - ramOffset] = output[z];
                        }
                    }

                } else { // float
                    // convert byte values
                    output = RomAttributeParser.floatToByte((float) getBinValue(), endian, table.getMemModelEndian());

                    for (int z = 0; z < 4; z++) { // insert in to file
                        binData[this.byteOffset + z + storageAddress - ramOffset] = output[z];
                    }
                }
        }

        //On the Bosch substract model, we need to update all previous cells, because they depend on our value
        if (isBoschSubtract && logicalIndex > 0) table.data[logicalIndex - 1].saveBinValueInFile();

        checkForDataUpdates();
    }

    public void registerDataCell(DataCell cell) {

        int memoryIndex = getMemoryStartAddress(cell);

        if (rom.byteCellMapping.containsKey(memoryIndex))
            {
            rom.byteCellMapping.get(memoryIndex).add(cell);
            }
        else {
            LinkedList<DataCell> l = new LinkedList<DataCell>();
            l.add(cell);
            rom.byteCellMapping.put(memoryIndex, l);
        }
    }

    public void checkForDataUpdates() {
        int memoryIndex = getMemoryStartAddress(this);

        if (rom.byteCellMapping.containsKey(memoryIndex)){
            for(DataCell c : rom.byteCellMapping.get(memoryIndex)) {
                c.updateBinValueFromMemory();
            }
        }
    }

    public static int getMemoryStartAddress(DataCell cell) {
        Table t = cell.getTable();
        return t.getStorageAddress() + cell.getByteOffset() - t.getRamOffset();
    }

    public Settings getSettings()
    {
        return SettingsManager.getSettings();
    }

    public void setSelected(boolean selected) {
        if (!table.isStaticDataTable() && this.isSelected != selected) {
            this.isSelected = selected;

            if (view!=null) {
                ECUEditorManager.getECUEditor().getTableToolBar().updateTableToolBar(table);
                view.drawCell();
            }
        }
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void updateBinValueFromMemory() {
        //We do this here because once we start populating all settings should be set
        if (minAllowedBin == 0 && maxAllowedBin == 0)
            calcValueRange();

        this.binValue = getValueFromMemory();
        updateView();
    }

    public void setDataView(DataCellView v) {
        view = v;
    }

    public int getIndexInTable() {
        return index;
    }

    private void updateView() {
        if (view != null) {
            view.drawCell();
        }
    }

    public Table getTable() {
        return this.table;
    }

    public String getStaticText() {
        return staticText;
    }

    public String getLiveValue() {
        return this.liveValue;
    }

    public void setLiveDataTraceValue(String liveValue) {
        if (this.liveValue != liveValue) {
            this.liveValue = liveValue;
            updateView();
        }
    }

    public double getBinValue() {
        return binValue;
    }

    public double getOriginalValue() {
        return originalValue;
    }

    public double getCompareToValue() {
        return compareToValue;
    }

    public double getRealValue() {
        if (table.getCurrentScale() == null) return binValue;

        return JEPUtil.evaluate(table.getCurrentScale().getExpression(), binValue);
    }

    public void setRealValue(String input) throws UserLevelException {
        // create parser
        input = input.replaceAll(DataCellView.REPLACE_TEXT, Settings.BLANK);
        try {
            double result = 0.0;
            if (!"x".equalsIgnoreCase(input)) {

            	// Optimization: If it has no scaling use the value directly
            	if(table.getCurrentScale().getExpression().trim().equalsIgnoreCase("x"))
				{
					result = NumberUtil.doubleValue(input);
				}
            	else if (table.getCurrentScale().getByteExpression() == null) {
                    result = table.getCurrentScale().approximateToByteFunction(NumberUtil.doubleValue(input), table.getStorageType(), table.isSignedData());
                }
                else {
                    result = JEPUtil.evaluate(table.getCurrentScale().getByteExpression(), NumberUtil.doubleValue(input));
                }

                if (table.getStorageType() != Settings.STORAGE_TYPE_FLOAT) {
                    result = (int) Math.round(result);
                }

                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("setRealValue: table=" + table.getName()
                            + " byteOffset=" + byteOffset + " logicalIndex=" + logicalIndex
                            + " input=" + input + " result=" + result + " binValue=" + binValue
                            + " scale=" + (table.getCurrentScale() != null ? table.getCurrentScale().getCategory() : "null"));

                if (binValue != result) {
                    this.setBinValue(result);
                } else {
                    LOGGER.warn("setRealValue: no change — binValue already equals result (" + binValue + ") for table=" + table.getName() + " cell=" + logicalIndex);
                }
            }
        } catch (ParseException e) {
            // Do nothing.  input is null or not a valid number.
        } catch (RuntimeException e) {
            LOGGER.error("setRealValue: unexpected exception for table=" + table.getName()
                    + " cell=" + logicalIndex + " input=" + input, e);
            throw e;
        }
    }

    public double getCompareValue() {
        return binValue - compareToValue;
    }

    public double getRealCompareValue() {
        return JEPUtil.evaluate(table.getCurrentScale().getExpression(), binValue) - JEPUtil.evaluate(table.getCurrentScale().getExpression(), compareToValue);
    }

    public double getRealCompareChangeValue() {
        double realBinValue = JEPUtil.evaluate(table.getCurrentScale().getExpression(), binValue);
        double realCompareValue = JEPUtil.evaluate(table.getCurrentScale().getExpression(), compareToValue);

        if (realCompareValue != 0.0) {
            // Compare change formula ((V2 - V1) / |V1|).
            return ((realBinValue - realCompareValue) / Math.abs(realCompareValue));
        } else {
            // Use this to avoid divide by 0 or infinite increase.
            return realBinValue - realCompareValue;
        }
    }

    public void setBinValue(double newBinValue) throws UserLevelException {
        if (binValue == newBinValue) {
            LOGGER.warn("setBinValue: skipped — already equals " + newBinValue + " for table=" + table.getName() + " cell=" + logicalIndex);
            return;
        }
        if (table.locked) {
            LOGGER.warn("setBinValue: skipped — table locked for table=" + table.getName() + " cell=" + logicalIndex);
            return;
        }
        if (table.getName().contains("Checksum Fix")) {
            return;
        }

        if (table.userLevel > getSettings().getUserLevel())
            throw new UserLevelException(table.userLevel);

        double checkedValue = newBinValue;

        // make sure it's in range
        if (checkedValue < minAllowedBin) {
            LOGGER.warn("setBinValue: clamped " + newBinValue + " up to minAllowedBin=" + minAllowedBin + " table=" + table.getName());
            checkedValue = minAllowedBin;
        }

        if (checkedValue > maxAllowedBin) {
            LOGGER.warn("setBinValue: clamped " + newBinValue + " down to maxAllowedBin=" + maxAllowedBin + " table=" + table.getName());
            checkedValue = maxAllowedBin;
        }

        if (binValue == checkedValue) {
            LOGGER.warn("setBinValue: skipped — after clamping, checkedValue=" + checkedValue + " equals binValue for table=" + table.getName() + " cell=" + logicalIndex);
            return;
        }

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("setBinValue: writing " + checkedValue + " to table=" + table.getName()
                    + " cell=" + logicalIndex + " byteOffset=" + byteOffset
                    + " addr=" + (table.getStorageAddress() + byteOffset - table.getRamOffset()));

        // set bin.
        binValue = checkedValue;
        saveBinValueInFile();
        updateView();
    }

    public void increment(double increment) throws UserLevelException {
        double oldValue = getRealValue();

        if (table.getCurrentScale().getCoarseIncrement() < 0.0) {
            increment = 0.0 - increment;
        }

        double incResult = 0;
        if (table.getCurrentScale().getByteExpression() == null) {
            incResult = table.getCurrentScale().approximateToByteFunction(oldValue + increment, table.getStorageType(), table.isSignedData());
        }
        else {
            incResult = JEPUtil.evaluate(table.getCurrentScale().getByteExpression(), (oldValue + increment));
        }

        if (table.getStorageType() == Settings.STORAGE_TYPE_FLOAT) {
            if (binValue != incResult) {
                this.setBinValue(incResult);
            }
        } else {
            int roundResult = (int) Math.round(incResult);
            if (binValue != roundResult) {
                this.setBinValue(roundResult);
            }
        }

        //Make sure we always change something. If the defined increment is too small this triggers
        //TODO: This should use real values
        if (table.getStorageType() != Settings.STORAGE_TYPE_FLOAT &&
                oldValue == getRealValue() &&
                ((increment > 0 && binValue < maxAllowedBin) || (increment < 0 && binValue > minAllowedBin))) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug(maxAllowedBin + " " + binValue);
            increment(increment * 2);
        }
    }

    public void undo() throws UserLevelException {
        this.setBinValue(originalValue);
    }

    public void setRevertPoint() {
        this.setOriginalValue(binValue);
        updateView();
    }

    public void setOriginalValue(double originalValue) {
        this.originalValue = originalValue;
    }

    public int getBitMask() {
        return this.bitMask;
    }

    public void setCompareValue(DataCell compareCell) {
        if (Settings.DataType.BIN == table.getCompareValueType())
        {
            if (this.compareToValue == compareCell.binValue) {
                return;
            }

            this.compareToValue = compareCell.binValue;
        } else {
            if (this.compareToValue == compareCell.originalValue) {
                return;
            }

            this.compareToValue = compareCell.originalValue;
        }
    }

    public void multiply(double factor) throws UserLevelException {
        String newValue = (getRealValue() * factor) + "";

        //We need to convert from dot to comma, in the case of EU Format.
        // This is because getRealValue to String has dot notation.
        if (NumberUtil.getSeperator() == ',') newValue = newValue.replace('.', ',');

        setRealValue(newValue);
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }

        if (!(other instanceof DataCell)) {
            return false;
        }

        DataCell otherCell = (DataCell) other;

        if (this.table.isStaticDataTable() != otherCell.table.isStaticDataTable()) {
            return false;
        }

        if (this.getBitMask() != otherCell.getBitMask()) {
            return false;
        }

        return binValue == otherCell.binValue;
    }

    public double getMaxAllowedBin() {
        return maxAllowedBin;
    }

    public double getMinAllowedBin() {
        return minAllowedBin;
    }

    @Override
    public String toString() {
        if (null == staticText || staticText.isEmpty()) {
            return String.valueOf(getRealValue());
        }
        return staticText;
    }
}
