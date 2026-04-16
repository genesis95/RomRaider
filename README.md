# RomRaider: Axis SkipCells Support

### Overview
This update introduces a fundamental enhancement to the **RomRaider** core logic, enabling the `skipCells` attribute for **1D Tables** and **Axis elements**. While RomRaider previously supported `skipCells` for 3D table bodies, this modification allows the software to correctly align axis data in ROMs (specifically **Toyota** platforms) where real data is interleaved with dummy bytes or padding.

### Modified Files & Core Logic

#### `DataCell.java`
* **Address/UI Decoupling**: Introduced a `logicalIndex` field to track the sequential grid position for UI rendering (0, 1, 2...) independently of the physical ROM offset.
* **Dual-Index Constructor**: Implemented `DataCell(Table, int logicalIndex, int byteIndex, Rom)` to allow the backend to read from non-contiguous memory while keeping the frontend sequential.
* **Backward Compatibility**: Maintained the standard constructor to treat `logicalIndex` and `index` as identical for legacy Subaru/non-skipped definitions.

#### `Table1D.java`
* **Property Extension**: Added the `skipCells` attribute to the `Table1D` class to mirror the behavior found in `Table3D`.
* **Stride-Based Population**: Overrode `populateTable()` to implement a stride calculation:
    * `strideBytes = storageType + skipCells`
    * `adjustedIndex = (i * strideBytes) / storageType`
* **Data Retrieval**: The logic now correctly "jumps" over padding bytes during the initial ROM dump, passing the calculated byte index to the new `DataCell` constructor.

#### `TableScaleUnmarshaller.java`
* **XML Definition Support**: Updated the XML unmarshaller logic to recognize and parse the `skipCells` attribute when it appears within nested `<table type="X Axis">` or `<table type="Y Axis">` elements.
* **Inheritance Handling**: Ensures that axis elements nested inside 3D table definitions correctly inherit the skip logic.

#### `DataCellView.java`
* **Dynamic Grid Rendering**: Updated the view logic to utilize the `logicalIndex` for vertical positioning. This ensures that the RomRaider UI displays a compact, readable table without "empty" rows representing the skipped ROM bytes.

### Example XML Definition
To utilize this RomRaider enhancement in your definition files:

```xml
<table name="Target Idle" address="0x12345">
    <table type="X Axis" address="0x12300" skipCells="1"/> 
</table>
